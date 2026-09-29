/*
 * From Lithium (https://github.com/CaffeineMC/lithium, LGPL-3.0-only), via DivineMC
 * (https://github.com/BX-Team/DivineMC, GPL-3.0). Shulker fixes get/remove(int), which used the
 * bucket index instead of the squared distance.
 */
package dev.shulker.server.util;

import it.unimi.dsi.fastutil.bytes.ByteBytePair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.LongJumpToRandomPos;
import org.jspecify.annotations.Nullable;

/**
 * The long jump targets around a mob, grouped by squared distance, so a target weighted by squared
 * distance (as vanilla picks them) is found without building a list of every block in range.
 * Bucket {@code i} holds the targets at squared distance {@code i + 1}.
 */
public final class LongJumpChoiceList extends AbstractList<LongJumpToRandomPos.PossibleJump> {

    /** Templates per range. Never mutated: {@link #forCenter} copies them. */
    private static final ConcurrentHashMap<ByteBytePair, LongJumpChoiceList> TEMPLATES = new ConcurrentHashMap<>();
    private static final LongJumpChoiceList FROG = new LongJumpChoiceList((byte) 4, (byte) 2);
    private static final LongJumpChoiceList GOAT = new LongJumpChoiceList((byte) 5, (byte) 5);

    private final BlockPos origin;
    private final IntArrayList[] packedOffsetsByDistanceSq;
    private final int[] weightByDistanceSq;
    private int totalWeight;

    private LongJumpChoiceList(final byte horizontalRange, final byte verticalRange) {
        this.origin = BlockPos.ZERO;
        final int maxDistanceSq = horizontalRange * horizontalRange * 2 + verticalRange * verticalRange;
        this.packedOffsetsByDistanceSq = new IntArrayList[maxDistanceSq];
        this.weightByDistanceSq = new int[maxDistanceSq];
        for (int x = -horizontalRange; x <= horizontalRange; x++) {
            for (int y = -verticalRange; y <= verticalRange; y++) {
                for (int z = -horizontalRange; z <= horizontalRange; z++) {
                    final int distanceSq = x * x + y * y + z * z;
                    if (distanceSq == 0) {
                        continue; // the mob's own position
                    }
                    IntArrayList offsets = this.packedOffsetsByDistanceSq[distanceSq - 1];
                    if (offsets == null) {
                        this.packedOffsetsByDistanceSq[distanceSq - 1] = offsets = new IntArrayList();
                    }
                    offsets.add(pack(x, y, z));
                    this.weightByDistanceSq[distanceSq - 1] += distanceSq;
                    this.totalWeight += distanceSq;
                }
            }
        }
    }

    private LongJumpChoiceList(final BlockPos origin, final IntArrayList[] packedOffsetsByDistanceSq, final int[] weightByDistanceSq, final int totalWeight) {
        this.origin = origin;
        this.packedOffsetsByDistanceSq = packedOffsetsByDistanceSq;
        this.weightByDistanceSq = weightByDistanceSq;
        this.totalWeight = totalWeight;
    }

    /** The targets around {@code center}; the ranges must be below 128. */
    public static LongJumpChoiceList forCenter(final BlockPos center, final byte horizontalRange, final byte verticalRange) {
        final LongJumpChoiceList template;
        if (horizontalRange == 4 && verticalRange == 2) {
            template = FROG;
        } else if (horizontalRange == 5 && verticalRange == 5) {
            template = GOAT;
        } else {
            template = TEMPLATES.computeIfAbsent(ByteBytePair.of(horizontalRange, verticalRange),
                key -> new LongJumpChoiceList(key.leftByte(), key.rightByte()));
        }
        final IntArrayList[] offsets = new IntArrayList[template.packedOffsetsByDistanceSq.length];
        for (int i = 0; i < offsets.length; i++) {
            if (template.packedOffsetsByDistanceSq[i] != null) {
                offsets[i] = template.packedOffsetsByDistanceSq[i].clone();
            }
        }
        return new LongJumpChoiceList(center, offsets, Arrays.copyOf(template.weightByDistanceSq, template.weightByDistanceSq.length), template.totalWeight);
    }

    /** Removes and returns a target, chosen with probability proportional to its squared distance, like vanilla. */
    public LongJumpToRandomPos.@Nullable PossibleJump removeRandomWeightedByDistanceSq(final RandomSource random) {
        if (this.totalWeight <= 0) {
            return null;
        }
        int target = random.nextInt(this.totalWeight);
        for (int index = 0; index < this.weightByDistanceSq.length; index++) {
            target -= this.weightByDistanceSq[index];
            if (target < 0) {
                final IntArrayList offsets = this.packedOffsetsByDistanceSq[index];
                return this.removeAt(index, random.nextInt(offsets.size()));
            }
        }
        return null;
    }

    private LongJumpToRandomPos.PossibleJump removeAt(final int bucket, final int elementIndex) {
        final IntArrayList offsets = this.packedOffsetsByDistanceSq[bucket];
        final int last = offsets.size() - 1;
        final int packed = offsets.getInt(elementIndex);
        offsets.set(elementIndex, offsets.getInt(last)); // order does not matter: swap with the last one
        offsets.removeInt(last);
        final int distanceSq = bucket + 1;
        this.weightByDistanceSq[bucket] -= distanceSq;
        this.totalWeight -= distanceSq;
        return this.jump(packed, distanceSq);
    }

    private LongJumpToRandomPos.PossibleJump jump(final int packed, final int distanceSq) {
        return new LongJumpToRandomPos.PossibleJump(this.origin.offset(unpackX(packed), unpackY(packed), unpackZ(packed)), distanceSq);
    }

    @Override
    public LongJumpToRandomPos.PossibleJump get(int index) {
        for (int bucket = 0; bucket < this.packedOffsetsByDistanceSq.length; bucket++) {
            final IntArrayList offsets = this.packedOffsetsByDistanceSq[bucket];
            if (offsets != null) {
                if (index < offsets.size()) {
                    return this.jump(offsets.getInt(index), bucket + 1);
                }
                index -= offsets.size();
            }
        }
        throw new IndexOutOfBoundsException();
    }

    @Override
    public LongJumpToRandomPos.PossibleJump remove(int index) {
        for (int bucket = 0; bucket < this.packedOffsetsByDistanceSq.length; bucket++) {
            final IntArrayList offsets = this.packedOffsetsByDistanceSq[bucket];
            if (offsets != null) {
                if (index < offsets.size()) {
                    return this.removeAt(bucket, index);
                }
                index -= offsets.size();
            }
        }
        throw new IndexOutOfBoundsException();
    }

    @Override
    public boolean isEmpty() {
        return this.totalWeight == 0;
    }

    @Override
    public int size() {
        int size = 0;
        for (final IntArrayList offsets : this.packedOffsetsByDistanceSq) {
            if (offsets != null) {
                size += offsets.size();
            }
        }
        return size;
    }

    private static int pack(final int x, final int y, final int z) {
        return (x + 128) | ((y + 128) << 8) | ((z + 128) << 16);
    }

    private static int unpackX(final int packed) {
        return (packed & 0xFF) - 128;
    }

    private static int unpackY(final int packed) {
        return ((packed >>> 8) & 0xFF) - 128;
    }

    private static int unpackZ(final int packed) {
        return ((packed >>> 16) & 0xFF) - 128;
    }
}
