package org.mycrimes.insecuretests.contacts;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.mycrimes.insecuretests.R;
import org.mycrimes.insecuretests.database.MessageTable;
import org.mycrimes.insecuretests.database.SignalDatabase;
import org.mycrimes.insecuretests.database.ThreadTable;
import org.mycrimes.insecuretests.dependencies.ApplicationDependencies;
import org.mycrimes.insecuretests.keyvalue.SignalStore;
import org.mycrimes.insecuretests.notifications.MarkReadReceiver;
import org.signal.core.util.concurrent.SimpleTask;

import java.util.List;

/**
 * Activity which displays a dialog to confirm whether to turn off "Contact Joined Signal" notifications.
 */
public class TurnOffContactJoinedNotificationsActivity extends AppCompatActivity {

  private final static String EXTRA_THREAD_ID = "thread_id";

  public static Intent newIntent(@NonNull Context context, long threadId) {
    Intent intent = new Intent(context, TurnOffContactJoinedNotificationsActivity.class);

    intent.putExtra(EXTRA_THREAD_ID, threadId);

    return intent;
  }

  @Override
  protected void onResume() {
    super.onResume();

    new MaterialAlertDialogBuilder(this)
        .setMessage(R.string.TurnOffContactJoinedNotificationsActivity__turn_off_contact_joined_signal)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
          handlePositiveAction(dialog);
        })
        .setNegativeButton(android.R.string.cancel, ((dialog, which) -> {
          dialog.dismiss();
          finish();
        }))
        .show();
  }

  private void handlePositiveAction(@NonNull DialogInterface dialog) {
    SimpleTask.run(getLifecycle(), () -> {
      ThreadTable threadTable = SignalDatabase.threads();

      List<MessageTable.MarkedMessageInfo> marked = threadTable.setRead(getIntent().getLongExtra(EXTRA_THREAD_ID, -1), false);
      MarkReadReceiver.process(this, marked);

      SignalStore.settings().setNotifyWhenContactJoinsSignal(false);
      ApplicationDependencies.getMessageNotifier().updateNotification(this);

      return null;
    }, unused -> {
      dialog.dismiss();
      finish();
    });
  }
}