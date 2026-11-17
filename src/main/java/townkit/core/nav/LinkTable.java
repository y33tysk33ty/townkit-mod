package townkit.core.nav;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * An immutable snapshot of transport links, as the route search reads them: a directed pair of node keys
 * ({@code NavNode.key(x, feetY, z)}) to its cost, whether it may be stepped and until when it is marked failed.
 * Every link has a row for each direction; the reverse row of a one-way link is never steppable, so Towntopia's own
 * step test can't let a walker through the wrong way. Rows are sorted by (from, to); lookups are binary searches over
 * {@code long[]} and allocate nothing, so worker threads may call them freely.
 *
 * <p>Published by {@link LinkInstaller} on the server thread through {@link NavBridge#publishLinks}.
 */
public final class LinkTable {
    /** One link as the installer hands it over: ends {@code a} -> {@code b} (node keys). */
    public record Link(long a, long b, int cost, boolean bothWays, boolean available, long failedUntilMillis) {
    }

    /** One direction of a link (build time only). */
    private record Row(long from, long to, int cost, boolean forward, boolean ok, long failedUntil) {
    }

    public static final LinkTable EMPTY = new LinkTable(new long[0], new long[0], new int[0], new boolean[0],
            new boolean[0], new long[0]);

    private final long[] froms;
    private final long[] tos;
    private final int[] costs;
    private final boolean[] forward;
    private final boolean[] ok;
    private final long[] failedUntil;

    private LinkTable(long[] froms, long[] tos, int[] costs, boolean[] forward, boolean[] ok, long[] failedUntil) {
        this.froms = froms;
        this.tos = tos;
        this.costs = costs;
        this.forward = forward;
        this.ok = ok;
        this.failedUntil = failedUntil;
    }

    /**
     * The table of {@code links}. The table is global (node keys carry no dimension), so the same pair can come
     * more than once (for example links on the same coordinates in two dimensions); each direction keeps the stricter
     * row: not steppable wins, then the later failure time, then the higher cost.
     */
    public static LinkTable of(Collection<Link> links) {
        if (links.isEmpty()) {
            return EMPTY;
        }
        List<Row> rows = new ArrayList<>(links.size() * 2);
        for (Link l : links) {
            rows.add(new Row(l.a(), l.b(), l.cost(), true, l.available(), l.failedUntilMillis()));
            rows.add(new Row(l.b(), l.a(), l.cost(), l.bothWays(), l.available() && l.bothWays(), l.failedUntilMillis()));
        }
        // the stricter row first: not steppable, then the later failure, then the higher cost
        rows.sort(Comparator.comparingLong(Row::from).thenComparingLong(Row::to)
                .thenComparing(Row::ok).thenComparing(Comparator.comparingLong(Row::failedUntil).reversed())
                .thenComparing(Comparator.comparingInt(Row::cost).reversed()).thenComparing(Row::forward));
        List<Row> kept = new ArrayList<>(rows.size());
        for (Row r : rows) {
            Row last = kept.isEmpty() ? null : kept.get(kept.size() - 1);
            if (last == null || last.from() != r.from() || last.to() != r.to()) {
                kept.add(r);
            }
        }
        int n = kept.size();
        long[] f = new long[n];
        long[] t = new long[n];
        int[] c = new int[n];
        boolean[] fw = new boolean[n];
        boolean[] k = new boolean[n];
        long[] fu = new long[n];
        for (int i = 0; i < n; i++) {
            Row r = kept.get(i);
            f[i] = r.from();
            t[i] = r.to();
            c[i] = r.cost();
            fw[i] = r.forward();
            k[i] = r.ok();
            fu[i] = r.failedUntil();
        }
        return new LinkTable(f, t, c, fw, k, fu);
    }

    /**
     * The table of {@code links} with none of them steppable: what is published when transport switches off, so the
     * bridge keeps refusing every installed pair (an edge left in a route map can't fall back to Towntopia's own step
     * test) while nothing can be ridden.
     */
    public static LinkTable refusing(Collection<Link> links) {
        List<Link> off = new ArrayList<>(links.size());
        for (Link l : links) {
            off.add(new Link(l.a(), l.b(), l.cost(), l.bothWays(), false, l.failedUntilMillis()));
        }
        return of(off);
    }

    public boolean isEmpty() {
        return froms.length == 0;
    }

    /** Number of rows (two per link, fewer when pairs repeat). */
    public int size() {
        return froms.length;
    }

    /** The row of the step {@code from -> to}, or -1 when that pair is not a link. */
    public int indexOf(long from, long to) {
        int lo = 0;
        int hi = froms.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long f = froms[mid];
            int cmp = f < from ? -1 : f > from ? 1 : Long.compare(tos[mid], to);
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** The first row leaving {@code from} (rows leaving it follow in order), or -1 when none does. */
    public int firstFrom(long from) {
        int lo = 0;
        int hi = froms.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (froms[mid] < from) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo < froms.length && froms[lo] == from ? lo : -1;
    }

    public long from(int row) {
        return froms[row];
    }

    public long to(int row) {
        return tos[row];
    }

    public int cost(int row) {
        return costs[row];
    }

    /** False for the reverse row of a one-way link: the step from its far end back to its start. */
    public boolean forward(int row) {
        return forward[row];
    }

    /** Whether the row's step may be taken at {@code nowMillis}: available, the right way, and not marked failed. */
    public boolean steppable(int row, long nowMillis) {
        return ok[row] && failedUntil[row] <= nowMillis;
    }
}
