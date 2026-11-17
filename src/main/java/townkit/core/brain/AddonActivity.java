package townkit.core.brain;

import com.alex.test.village.brain.Activity;

/** An addon activity as Towntopia sees it: a plain activity that routine things may interrupt. */
final class AddonActivity extends Activity {
    final AddonRun run;

    AddonActivity(AddonRun run) {
        this.run = run;
    }

    @Override
    protected void start(Activity.Context ctx) {
        this.run.start(ctx);
    }

    @Override
    protected Activity.Status tick(Activity.Context ctx) {
        return this.run.tick(ctx);
    }

    @Override
    public void stop(Activity.Context ctx) {
        this.run.stop(ctx);
    }

    @Override
    public String name() {
        return this.run.id().toString();
    }
}
