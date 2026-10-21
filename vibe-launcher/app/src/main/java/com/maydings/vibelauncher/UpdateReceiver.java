package com.maydings.vibelauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Hears back from Android's installer about an update (or SmartTube) Updater handed it: shows its confirmation screen,
 * or says why it didn't install. Installing ends the launcher, and some TVs then leave the screen blank
 * until Home is pressed, so once the new version is in, this brings Home back.
 */
public class UpdateReceiver extends BroadcastReceiver {

    /** True when the install was another app (SmartTube), not this one's update. */
    static final String EXTRA_OTHER_APP = "otherApp";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            // Only after an update from here: one installed from a computer mustn't take over the screen.
            if (Updater.justUpdated(context)) {
                context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            boolean other = intent.getBooleanExtra(EXTRA_OTHER_APP, false);
            if (!other) Updater.installStopped(context);
            // Aborted is the user pressing Cancel: nothing to say.
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                int message = other ? R.string.smarttube_failed : R.string.update_failed;
                Toast.makeText(context, Lang.wrap(context).getString(message), Toast.LENGTH_LONG).show();
            }
        }
    }
}
