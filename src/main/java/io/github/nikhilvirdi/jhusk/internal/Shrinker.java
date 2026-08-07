package io.github.nikhilvirdi.jhusk.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The production byte-buffer shrinking pipeline.
 *
 * <p>Every candidate is evaluated exclusively through {@link ShrinkHarness}. Spans are
 * structural metadata only; the harness captures replacement metadata when it accepts a
 * candidate, so passes never replay a buffer or make an independent validity decision.</p>
 */
public final class Shrinker<T> {

    private static final Set<String> PRIMITIVE_LABELS =
        Set.of("bool", "int", "long", "char", "double");

    private final ShrinkHarness<T> harness;
    private byte[] current;
    private List<Span> rootSpans;

    public Shrinker(ShrinkHarness<T> harness) {
        this.harness = harness;
    }

    /** Shrinks an already-known failing buffer using spans from the same execution. */
    public byte[] shrink(byte[] failingBuffer, List<Span> failingRootSpans) {
        current = failingBuffer.clone();
        rootSpans = List.copyOf(failingRootSpans);
        try {
            boolean changed;
            do {
                changed = spanDeletion();
                changed |= zeroing();
                changed |= blockLowering();
                changed |= blockDeduplication();
                changed |= normalizeLists();
            } while (changed);
        } catch (ShrinkHarness.AttemptBoundExceededException ignored) {
            // current is always the smallest accepted shortlex buffer observed so far.
        }
        return current.clone();
    }

    /** Removes coherent spans, trying the largest regions first. */
    private boolean spanDeletion() {
        boolean changed = false;
        while (true) {
            List<Span> spans = allSpans();
            spans.sort(Comparator.comparingInt(Span::size).reversed()
                .thenComparingInt(Span::getStart));
            boolean accepted = false;
            for (Span span : spans) {
                if (span.size() > 0 && tryCandidate(splice(current, span.getStart(), span.getEnd(), new byte[0]))) {
                    changed = accepted = true;
                    break;
                }
            }
            if (!accepted) return changed;
        }
    }

    /** Attempts complete span/block zeroing before the more expensive lowering pass. */
    private boolean zeroing() {
        boolean changed = false;
        while (true) {
            List<Span> spans = allSpans();
            spans.sort(Comparator.comparingInt(Span::size).reversed()
                .thenComparingInt(Span::getStart));
            boolean accepted = false;
            for (Span span : spans) {
                if (span.size() == 0 || isZero(current, span.getStart(), span.getEnd())) continue;
                byte[] candidate = current.clone();
                Arrays.fill(candidate, span.getStart(), span.getEnd(), (byte) 0);
                if (tryCandidate(candidate)) {
                    changed = accepted = true;
                    break;
                }
            }
            if (!accepted) return changed;
        }
    }

    /** Binary-searches primitive fixed-width blocks toward their all-zero shrink target. */
    private boolean blockLowering() {
        boolean changed = false;
        while (true) {
            boolean accepted = false;
            for (Span block : primitiveBlocks()) {
                if (lowerBlock(block)) {
                    changed = accepted = true;
                    break;
                }
            }
            if (!accepted) return changed;
        }
    }

    private boolean lowerBlock(Span block) {
        byte[] high = slice(current, block);
        byte[] low = new byte[high.length];
        while (compareBytes(low, high) < 0) {
            byte[] middle = midpoint(low, high);
            if (compareBytes(middle, low) == 0) return false;
            byte[] candidate = current.clone();
            System.arraycopy(middle, 0, candidate, block.getStart(), middle.length);
            if (tryCandidate(candidate)) return true;
            low = increment(middle);
        }
        return false;
    }

    /** Reuses smaller/equal primitive blocks already present in the buffer. */
    private boolean blockDeduplication() {
        boolean changed = false;
        while (true) {
            List<Span> blocks = primitiveBlocks();
            boolean accepted = false;
            for (Span target : blocks) {
                byte[] targetBytes = slice(current, target);
                List<byte[]> replacements = new ArrayList<>();
                for (Span source : blocks) {
                    if (source == target || source.size() != target.size()) continue;
                    byte[] sourceBytes = slice(current, source);
                    if (compareBytes(sourceBytes, targetBytes) <= 0) replacements.add(sourceBytes);
                }
                replacements.sort(Shrinker::compareBytes);
                for (byte[] replacement : distinct(replacements)) {
                    byte[] candidate = current.clone();
                    System.arraycopy(replacement, 0, candidate, target.getStart(), replacement.length);
                    if (tryCandidate(candidate)) {
                        changed = accepted = true;
                        break;
                    }
                }
                if (accepted) break;
            }
            if (!accepted) return changed;
        }
    }

    /**
     * Heuristic normalization only: lists may be order-sensitive, so a sorted raw-byte order
     * is retained solely when the harness reproduces the original failure.
     */
    private boolean normalizeLists() {
        boolean changed = false;
        while (true) {
            boolean accepted = false;
            for (Span list : allSpans()) {
                if (!"list".equals(list.getLabel())) continue;
                List<Span> elements = new ArrayList<>();
                for (Span child : list.getChildren()) {
                    if ("list-element".equals(child.getLabel())) elements.add(child);
                }
                Span terminator = null;
                if (!elements.isEmpty()) {
                    Span last = elements.get(elements.size() - 1);
                    if (last.size() == 1 && last.getChildren().isEmpty()) {
                        terminator = elements.remove(elements.size() - 1);
                    }
                }
                if (elements.size() < 2) continue;
                List<byte[]> chunks = new ArrayList<>();
                for (Span element : elements) chunks.add(slice(current, element));
                chunks.sort(Shrinker::compareBytes);
                byte[] replacement = join(chunks, terminator == null ? null : slice(current, terminator));
                int start = elements.get(0).getStart();
                int end = terminator == null ? elements.get(elements.size() - 1).getEnd() : terminator.getEnd();
                if (tryCandidate(splice(current, start, end, replacement))) {
                    changed = accepted = true;
                    break;
                }
            }
            if (!accepted) return changed;
        }
    }

    private boolean tryCandidate(byte[] candidate) {
        if (ShrinkHarness.compareShortlex(candidate, current) >= 0 || !harness.tryBuffer(candidate)) return false;
        current = candidate;
        rootSpans = harness.getLastAcceptedRootSpans();
        return true;
    }

    private List<Span> allSpans() {
        List<Span> spans = new ArrayList<>();
        for (Span root : rootSpans) collect(root, spans);
        return spans;
    }

    private List<Span> primitiveBlocks() {
        List<Span> blocks = new ArrayList<>();
        for (Span span : allSpans()) {
            if (PRIMITIVE_LABELS.contains(span.getLabel()) && span.size() > 0) blocks.add(span);
        }
        blocks.sort(Comparator.comparingInt(Span::getStart));
        return blocks;
    }

    private static void collect(Span span, List<Span> spans) {
        spans.add(span);
        for (Span child : span.getChildren()) collect(child, spans);
    }

    /** Phase 9-style byte splice for span deletion and collection reordering. */
    private static byte[] splice(byte[] source, int from, int to, byte[] replacement) {
        byte[] result = new byte[source.length - (to - from) + replacement.length];
        System.arraycopy(source, 0, result, 0, from);
        System.arraycopy(replacement, 0, result, from, replacement.length);
        System.arraycopy(source, to, result, from + replacement.length, source.length - to);
        return result;
    }

    private static byte[] slice(byte[] source, Span span) {
        return Arrays.copyOfRange(source, span.getStart(), span.getEnd());
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int comparison = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }

    private static byte[] midpoint(byte[] low, byte[] high) {
        byte[] difference = new byte[low.length];
        int borrow = 0;
        for (int i = low.length - 1; i >= 0; i--) {
            int value = (high[i] & 0xFF) - (low[i] & 0xFF) - borrow;
            if (value < 0) {
                value += 256;
                borrow = 1;
            } else {
                borrow = 0;
            }
            difference[i] = (byte) value;
        }

        byte[] halfDifference = new byte[low.length];
        int remainder = 0;
        for (int i = 0; i < low.length; i++) {
            int value = (remainder << 8) | (difference[i] & 0xFF);
            halfDifference[i] = (byte) (value >>> 1);
            remainder = value & 1;
        }

        byte[] result = low.clone();
        int carry = 0;
        for (int i = result.length - 1; i >= 0; i--) {
            int value = (result[i] & 0xFF) + (halfDifference[i] & 0xFF) + carry;
            result[i] = (byte) value;
            carry = value >>> 8;
        }
        return result;
    }

    private static byte[] increment(byte[] bytes) {
        byte[] result = bytes.clone();
        for (int i = result.length - 1; i >= 0; i--) {
            if (++result[i] != 0) return result;
        }
        return result;
    }

    private static List<byte[]> distinct(List<byte[]> values) {
        List<byte[]> result = new ArrayList<>();
        for (byte[] value : values) {
            if (result.isEmpty() || compareBytes(result.get(result.size() - 1), value) != 0) result.add(value);
        }
        return result;
    }

    private static byte[] join(List<byte[]> chunks, byte[] suffix) {
        int length = suffix == null ? 0 : suffix.length;
        for (byte[] chunk : chunks) length += chunk.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        if (suffix != null) System.arraycopy(suffix, 0, result, offset, suffix.length);
        return result;
    }
}
