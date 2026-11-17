package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.nav.TransportLink;

/** Edge bookkeeping when transport switches off in the middle of a recompute (no level: the edge ops are faked). */
class LinkInstallerTest {
    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("townkit_test", "hop");

    private static TransportLink link(int x) {
        return new TransportLink(KIND, new BlockPos(x, 64, 0), new BlockPos(x, 64, 5), 3, true);
    }

    @BeforeEach
    @AfterEach
    void reset() {
        LinkInstaller.resetForTests();
    }

    /** Removing a gone link's edge switched transport off, so no new link may get an edge. */
    @Test
    void applyEdgesStopsAfterSwitchOff() {
        TransportLink gone = link(0);
        List<TransportLink> out = List.of(link(10), link(20));
        List<TransportLink> addCalls = new ArrayList<>();
        LinkInstaller.EdgeOps ops = new LinkInstaller.EdgeOps() {
            @Override
            public void add(TransportLink l) {
                addCalls.add(l);
            }

            @Override
            public void remove(TransportLink l) {
                LinkInstaller.switchOff("test", null);
            }
        };
        List<TransportLink> added = LinkInstaller.applyEdges(ops, List.of(gone), Set.copyOf(out), out);
        assertEquals(List.of(), added);
        assertEquals(List.of(), addCalls);
        assertTrue(LinkInstaller.isOff());
    }

    /**
     * Adding the 2nd of 3 new links failed. Every new pair is published refused, the 1st included (its edge
     * went in), and that edge is taken out again.
     */
    @Test
    void addFailureRefusesEveryNewLink() {
        List<TransportLink> out = List.of(link(10), link(20), link(30));
        List<TransportLink> removed = new ArrayList<>();
        LinkInstaller.EdgeOps ops = new LinkInstaller.EdgeOps() {
            @Override
            public void add(TransportLink l) {
                if (l.equals(out.get(1))) {
                    throw new IllegalStateException("addNeighbour failed");
                }
            }

            @Override
            public void remove(TransportLink l) {
                removed.add(l);
            }
        };
        LinkInstaller.applyEdges(ops, List.of(), Set.copyOf(out), out);
        assertTrue(LinkInstaller.isOff());
        LinkTable table = NavBridge.links();
        long now = System.currentTimeMillis();
        for (TransportLink l : out) {
            long a = LinkInstaller.key(l.from());
            long b = LinkInstaller.key(l.to());
            int ab = table.indexOf(a, b);
            int ba = table.indexOf(b, a);
            assertTrue(ab >= 0 && ba >= 0, "pair in the table: " + l);
            assertFalse(table.steppable(ab, now), "refused: " + l);
            assertFalse(table.steppable(ba, now), "refused: " + l);
        }
        assertEquals(List.of(out.get(0)), removed);
    }

    private static void assertRefused(TransportLink l) {
        LinkTable table = NavBridge.links();
        long a = LinkInstaller.key(l.from());
        long b = LinkInstaller.key(l.to());
        int ab = table.indexOf(a, b);
        int ba = table.indexOf(b, a);
        assertTrue(ab >= 0 && ba >= 0, "pair in the table: " + l);
        assertFalse(table.steppable(ab, System.currentTimeMillis()), "refused: " + l);
        assertFalse(table.steppable(ba, System.currentTimeMillis()), "refused: " + l);
    }

    /** Waits (up to 5 s) until {@code t} is blocked on a monitor. */
    private static void awaitBlocked(Thread t) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (t.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > end) {
                throw new AssertionError("thread never blocked on the installer lock: " + t.getState());
            }
            Thread.sleep(1);
        }
    }

    /**
     * Review fix: off flips after the last edge went in. The village is not installed, every new pair is refused and
     * every added edge (the last one included) is taken out.
     */
    @Test
    void switchOffAfterTheLastAddNeverInstalls() {
        List<TransportLink> out = List.of(link(10), link(20));
        List<TransportLink> removed = new ArrayList<>();
        LinkInstaller.EdgeOps ops = new LinkInstaller.EdgeOps() {
            @Override
            public void add(TransportLink l) {
                if (l.equals(out.get(1))) {
                    LinkInstaller.switchOff("test", null);
                }
            }

            @Override
            public void remove(TransportLink l) {
                removed.add(l);
            }
        };
        boolean[] installed = {false};
        List<TransportLink> added = LinkInstaller.applyEdges(ops, List.of(), Set.copyOf(out), out,
                () -> installed[0] = true);
        assertEquals(out, added);
        assertFalse(installed[0]);
        out.forEach(LinkInstallerTest::assertRefused);
        assertEquals(out, removed);
    }

    /**
     * Review fix: a switch-off on another thread waits while a recompute installs its links (so its snapshot holds
     * them and refuses them), and runs once the install is done.
     */
    @Test
    void switchOffWaitsForTheInstall() throws InterruptedException {
        List<TransportLink> out = List.of(link(10));
        LinkInstaller.EdgeOps none = new LinkInstaller.EdgeOps() {
            @Override
            public void add(TransportLink l) {
            }

            @Override
            public void remove(TransportLink l) {
            }
        };
        Thread[] other = new Thread[1];
        boolean[] offDuringInstall = {true};
        LinkInstaller.applyEdges(none, List.of(), Set.copyOf(out), out, () -> {
            other[0] = new Thread(() -> LinkInstaller.switchOff("test", null));
            other[0].start();
            try {
                awaitBlocked(other[0]);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            offDuringInstall[0] = LinkInstaller.isOff();
        });
        other[0].join(5000);
        assertFalse(offDuringInstall[0]);
        assertTrue(LinkInstaller.isOff());
    }

    /**
     * Review fix: a publish that started before the switch-off can't land a steppable table over the refused one: it
     * waits for the lock, then sees off and publishes nothing.
     */
    @Test
    void publishNeverLandsAfterSwitchOff() throws InterruptedException {
        TransportLink l = link(10);
        Thread publisher = new Thread(LinkInstaller::publish);
        synchronized (LinkInstaller.class) {
            publisher.start();
            awaitBlocked(publisher);
            LinkInstaller.switchOff("test", null);
            LinkInstaller.refuse(List.of(new LinkTable.Link(LinkInstaller.key(l.from()), LinkInstaller.key(l.to()),
                    l.cost(), l.bothWays(), false, 0L)));
        }
        publisher.join(5000);
        assertRefused(l);
    }
}
