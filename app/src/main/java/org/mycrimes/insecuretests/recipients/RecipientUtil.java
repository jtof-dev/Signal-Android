package org.mycrimes.insecuretests.recipients;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.mycrimes.insecuretests.contacts.sync.ContactDiscovery;
import org.mycrimes.insecuretests.database.GroupTable;
import org.mycrimes.insecuretests.database.RecipientTable.RegisteredState;
import org.mycrimes.insecuretests.database.SignalDatabase;
import org.mycrimes.insecuretests.database.ThreadTable;
import org.mycrimes.insecuretests.database.model.GroupRecord;
import org.mycrimes.insecuretests.dependencies.ApplicationDependencies;
import org.mycrimes.insecuretests.groups.GroupChangeBusyException;
import org.mycrimes.insecuretests.groups.GroupChangeException;
import org.mycrimes.insecuretests.groups.GroupChangeFailedException;
import org.mycrimes.insecuretests.groups.GroupManager;
import org.mycrimes.insecuretests.jobs.MultiDeviceBlockedUpdateJob;
import org.mycrimes.insecuretests.jobs.MultiDeviceMessageRequestResponseJob;
import org.mycrimes.insecuretests.jobs.RefreshOwnProfileJob;
import org.mycrimes.insecuretests.jobs.RotateProfileKeyJob;
import org.mycrimes.insecuretests.keyvalue.SignalStore;
import org.mycrimes.insecuretests.mms.OutgoingMessage;
import org.mycrimes.insecuretests.sms.MessageSender;
import org.mycrimes.insecuretests.storage.StorageSyncHelper;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class RecipientUtil {

  private static final String TAG = Log.tag(RecipientUtil.class);

  /**
   * This method will do it's best to get a {@link ServiceId} for the provided recipient. This includes performing
   * a possible network request if no ServiceId is available. If the request to get a ServiceId fails or the user is
   * not registered, an IOException is thrown.
   */
  @WorkerThread
  public static @NonNull ServiceId getOrFetchServiceId(@NonNull Context context, @NonNull Recipient recipient) throws IOException {
    return toSignalServiceAddress(context, recipient).getServiceId();
  }

  /**
   * This method will do it's best to craft a fully-populated {@link SignalServiceAddress} based on
   * the provided recipient. This includes performing a possible network request if no UUID is
   * available. If the request to get a UUID fails or the user is not registered, an IOException is thrown.
   */
  @WorkerThread
  public static @NonNull SignalServiceAddress toSignalServiceAddress(@NonNull Context context, @NonNull Recipient recipient)
      throws IOException
  {
    recipient = recipient.resolve();

    if (!recipient.getServiceId().isPresent() && !recipient.getE164().isPresent()) {
      throw new AssertionError(recipient.getId() + " - No UUID or phone number!");
    }

    if (!recipient.getServiceId().isPresent()) {
      Log.i(TAG, recipient.getId() + " is missing a UUID...");
      RegisteredState state = ContactDiscovery.refresh(context, recipient, false);

      recipient = Recipient.resolved(recipient.getId());
      Log.i(TAG, "Successfully performed a UUID fetch for " + recipient.getId() + ". Registered: " + state);
    }

    if (recipient.hasServiceId()) {
      return new SignalServiceAddress(recipient.requireServiceId(), Optional.ofNullable(recipient.resolve().getE164().orElse(null)));
    } else {
      throw new NotFoundException(recipient.getId() + " is not registered!");
    }
  }

  public static @NonNull List<SignalServiceAddress> toSignalServiceAddresses(@NonNull Context context, @NonNull List<RecipientId> recipients)
      throws IOException
  {
    return toSignalServiceAddressesFromResolved(context, Recipient.resolvedList(recipients));
  }

  public static @NonNull List<SignalServiceAddress> toSignalServiceAddressesFromResolved(@NonNull Context context, @NonNull List<Recipient> recipients)
      throws IOException
  {
    ensureUuidsAreAvailable(context, recipients);

    return Stream.of(recipients)
                 .map(Recipient::resolve)
                 .map(r -> new SignalServiceAddress(r.requireServiceId(), r.getE164().orElse(null)))
                 .toList();
  }

  /**
   * Ensures that UUIDs are available. If a UUID cannot be retrieved or a user is found to be unregistered, an exception is thrown.
   */
  public static boolean ensureUuidsAreAvailable(@NonNull Context context, @NonNull Collection<Recipient> recipients)
      throws IOException
  {
    List<Recipient> recipientsWithoutUuids = Stream.of(recipients)
                                                   .map(Recipient::resolve)
                                                   .filterNot(Recipient::hasServiceId)
                                                   .toList();

    if (recipientsWithoutUuids.size() > 0) {
      ContactDiscovery.refresh(context, recipientsWithoutUuids, false);

      if (recipients.stream().map(Recipient::resolve).anyMatch(Recipient::isUnregistered)) {
        throw new NotFoundException("1 or more recipients are not registered!");
      }

      return true;
    } else {
      return false;
    }
  }

  public static boolean isBlockable(@NonNull Recipient recipient) {
    Recipient resolved = recipient.resolve();
    return !resolved.isMmsGroup();
  }

  public static List<Recipient> getEligibleForSending(@NonNull List<Recipient> recipients) {
    return Stream.of(recipients)
                 .filter(r -> r.getRegistered() != RegisteredState.NOT_REGISTERED)
                 .filter(r -> !r.isBlocked())
                 .toList();
  }

  /**
   * You can call this for non-groups and not have to handle any network errors.
   */
  @WorkerThread
  public static void blockNonGroup(@NonNull Context context, @NonNull Recipient recipient) {
    if (recipient.isGroup()) {
      throw new AssertionError();
    }

    try {
      block(context, recipient);
    } catch (GroupChangeException | IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * You can call this for any type of recipient but must handle network errors that can occur from
   * GV2.
   * <p>
   * GV2 operations can also take longer due to the network.
   */
  @WorkerThread
  public static void block(@NonNull Context context, @NonNull Recipient recipient)
      throws GroupChangeBusyException, IOException, GroupChangeFailedException
  {
    if (!isBlockable(recipient)) {
      throw new AssertionError("Recipient is not blockable!");
    }
    Log.w(TAG, "Blocking " + recipient.getId() + " (group: " + recipient.isGroup() + ")");

    recipient = recipient.resolve();

    if (recipient.isGroup() && recipient.getGroupId().get().isPush()) {
      GroupManager.leaveGroupFromBlockOrMessageRequest(context, recipient.getGroupId().get().requirePush());
    }

    SignalDatabase.recipients().setBlocked(recipient.getId(), true);

    if (recipient.isSystemContact() || recipient.isProfileSharing() || isProfileSharedViaGroup(recipient)) {
      SignalDatabase.recipients().setProfileSharing(recipient.getId(), false);

      ApplicationDependencies.getJobManager().startChain(new RefreshOwnProfileJob())
                                             .then(new RotateProfileKeyJob())
                                             .enqueue();
    }

    ApplicationDependencies.getJobManager().add(new MultiDeviceBlockedUpdateJob());
    StorageSyncHelper.scheduleSyncForDataChange();
  }

  @WorkerThread
  public static void unblock(@NonNull Recipient recipient) {
    if (!isBlockable(recipient)) {
      throw new AssertionError("Recipient is not blockable!");
    }
    Log.i(TAG, "Unblocking " + recipient.getId() + " (group: " + recipient.isGroup() + ")", new Throwable());

    SignalDatabase.recipients().setBlocked(recipient.getId(), false);
    SignalDatabase.recipients().setProfileSharing(recipient.getId(), true);
    ApplicationDependencies.getJobManager().add(new MultiDeviceBlockedUpdateJob());
    StorageSyncHelper.scheduleSyncForDataChange();
  }

  @WorkerThread
  public static boolean isRecipientHidden(long threadId) {
    if (threadId < 0) {
      return false;
    }

    ThreadTable threadTable     = SignalDatabase.threads();
    Recipient   threadRecipient = threadTable.getRecipientForThreadId(threadId);

    if (threadRecipient == null) {
      return false;
    }

    return threadRecipient.isHidden();
  }

  /**
   * If true, the new message request UI does not need to be shown, and it's safe to send read
   * receipts.
   *
   * Note that this does not imply that a user has explicitly accepted a message request -- it could
   * also be the case that the thread in question is for a system contact or something of the like.
   */
  @WorkerThread
  public static boolean isMessageRequestAccepted(@NonNull Context context, long threadId) {
    if (threadId < 0) {
      return true;
    }

    ThreadTable threadTable     = SignalDatabase.threads();
    Recipient   threadRecipient = threadTable.getRecipientForThreadId(threadId);

    if (threadRecipient == null) {
      return true;
    }

    return isMessageRequestAccepted(threadId, threadRecipient);
  }

  /**
   * See {@link #isMessageRequestAccepted(Context, long)}.
   */
  @WorkerThread
  public static boolean isMessageRequestAccepted(@NonNull Context context, @Nullable Recipient threadRecipient) {
    if (threadRecipient == null) {
      return true;
    }

    Long threadId = SignalDatabase.threads().getThreadIdFor(threadRecipient.getId());
    return isMessageRequestAccepted(threadId, threadRecipient);
  }

  /**
   * Like {@link #isMessageRequestAccepted(Context, long)} but with fewer checks around messages so it
   * is more likely to return false.
   */
  @WorkerThread
  public static boolean isCallRequestAccepted(@Nullable Recipient threadRecipient) {
    if (threadRecipient == null) {
      return true;
    }

    Long threadId = SignalDatabase.threads().getThreadIdFor(threadRecipient.getId());
    return isCallRequestAccepted(threadId, threadRecipient);
  }

  /**
   * @return True if a conversation existed before we enabled message requests, otherwise false.
   */
  @WorkerThread
  public static boolean isPreMessageRequestThread(@Nullable Long threadId) {
    long beforeTime = SignalStore.misc().getMessageRequestEnableTime();
    return threadId != null && SignalDatabase.messages().getMessageCountForThread(threadId, beforeTime) > 0;
  }

  @WorkerThread
  public static void shareProfileIfFirstSecureMessage(@NonNull Recipient recipient) {
    if (recipient.isProfileSharing()) {
      return;
    }

    long threadId = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());

    if (isPreMessageRequestThread(threadId)) {
      return;
    }

    boolean firstMessage = SignalDatabase.messages().getOutgoingSecureMessageCount(threadId) == 0;

    if (firstMessage) {
      SignalDatabase.recipients().setProfileSharing(recipient.getId(), true);
    }
  }

  public static boolean isLegacyProfileSharingAccepted(@NonNull Recipient threadRecipient) {
    return threadRecipient.isSelf()           ||
           threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact()  ||
           !threadRecipient.isRegistered()    ||
           threadRecipient.isForceSmsSelection() ||
           threadRecipient.isHidden();
  }

  /**
   * @return True if this recipient should already have your profile key, otherwise false.
   */
  public static boolean shouldHaveProfileKey(@NonNull Recipient recipient) {
    if (recipient.isBlocked()) {
      return false;
    }

    if (recipient.isProfileSharing()) {
      return true;
    } else {
      GroupTable groupDatabase = SignalDatabase.groups();
      return groupDatabase.getPushGroupsContainingMember(recipient.getId())
                          .stream()
                          .anyMatch(GroupRecord::isV2Group);

    }
  }

  /**
   * Checks if a universal timer is set and if the thread should have it set on it. Attempts to abort quickly and perform
   * minimal database access.
   */
  @WorkerThread
  public static boolean setAndSendUniversalExpireTimerIfNecessary(@NonNull Context context, @NonNull Recipient recipient, long threadId) {
    int defaultTimer = SignalStore.settings().getUniversalExpireTimer();
    if (defaultTimer == 0 || recipient.isGroup() || recipient.isDistributionList() || recipient.getExpiresInSeconds() != 0 || !recipient.isRegistered()) {
      return false;
    }

    if (threadId == -1 || SignalDatabase.messages().canSetUniversalTimer(threadId)) {
      SignalDatabase.recipients().setExpireMessages(recipient.getId(), defaultTimer);
      OutgoingMessage outgoingMessage = OutgoingMessage.expirationUpdateMessage(recipient, System.currentTimeMillis(), defaultTimer * 1000L);
      MessageSender.send(context, outgoingMessage, SignalDatabase.threads().getOrCreateThreadIdFor(recipient), MessageSender.SendType.SIGNAL, null, null);
      return true;
    }
    return false;
  }

  @WorkerThread
  public static boolean isMessageRequestAccepted(@Nullable Long threadId, @Nullable Recipient threadRecipient) {
    return threadRecipient == null ||
           threadRecipient.isSelf() ||
           threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact() ||
           threadRecipient.isForceSmsSelection() ||
           !threadRecipient.isRegistered() ||
           (!threadRecipient.isHidden() && (
               hasSentMessageInThread(threadId) ||
               noSecureMessagesAndNoCallsInThread(threadId) ||
               isPreMessageRequestThread(threadId))
           );
  }

  @WorkerThread
  private static boolean isCallRequestAccepted(@Nullable Long threadId, @NonNull Recipient threadRecipient) {
    return threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact() ||
           hasSentMessageInThread(threadId) ||
           isPreMessageRequestThread(threadId);
  }

  @WorkerThread
  public static boolean hasSentMessageInThread(@Nullable Long threadId) {
    return threadId != null && SignalDatabase.messages().getOutgoingSecureMessageCount(threadId) != 0;
  }

  public static boolean isSmsOnly(long threadId, @NonNull Recipient threadRecipient) {
    return !threadRecipient.isRegistered() ||
           noSecureMessagesAndNoCallsInThread(threadId);
  }

  @WorkerThread
  private static boolean noSecureMessagesAndNoCallsInThread(@Nullable Long threadId) {
    if (threadId == null) {
      return true;
    }

    return SignalDatabase.messages().getSecureMessageCount(threadId) == 0 &&
           !SignalDatabase.threads().hasReceivedAnyCallsSince(threadId, 0);
  }

  @WorkerThread
  private static boolean isProfileSharedViaGroup(@NonNull Recipient recipient) {
    return Stream.of(SignalDatabase.groups().getPushGroupsContainingMember(recipient.getId()))
                 .anyMatch(group -> Recipient.resolved(group.getRecipientId()).isProfileSharing());
  }
}