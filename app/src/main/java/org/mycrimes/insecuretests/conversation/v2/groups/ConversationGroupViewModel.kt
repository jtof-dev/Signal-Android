package org.mycrimes.insecuretests.conversation.v2.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.Subject
import org.signal.core.util.Result
import org.signal.core.util.concurrent.subscribeWithSubject
import org.mycrimes.insecuretests.conversation.v2.ConversationRecipientRepository
import org.mycrimes.insecuretests.database.GroupTable
import org.mycrimes.insecuretests.database.model.GroupRecord
import org.mycrimes.insecuretests.groups.ui.GroupChangeFailureReason
import org.mycrimes.insecuretests.groups.v2.GroupBlockJoinRequestResult
import org.mycrimes.insecuretests.groups.v2.GroupManagementRepository
import org.mycrimes.insecuretests.profiles.spoofing.ReviewUtil
import org.mycrimes.insecuretests.recipients.Recipient

/**
 * Manages group state and actions for conversations.
 */
class ConversationGroupViewModel(
  private val threadId: Long,
  private val groupManagementRepository: GroupManagementRepository = GroupManagementRepository(),
  private val recipientRepository: ConversationRecipientRepository
) : ViewModel() {

  private val disposables = CompositeDisposable()
  private val _groupRecord: BehaviorSubject<GroupRecord>
  private val _reviewState: Subject<ConversationGroupReviewState>

  private val _groupActiveState: Subject<ConversationGroupActiveState> = BehaviorSubject.create()
  private val _memberLevel: BehaviorSubject<ConversationGroupMemberLevel> = BehaviorSubject.create()

  val groupRecordSnapshot: GroupRecord?
    get() = _groupRecord.value

  init {
    _groupRecord = recipientRepository
      .groupRecord
      .filter { it.isPresent }
      .map { it.get() }
      .subscribeWithSubject(BehaviorSubject.create(), disposables)

    val duplicates = _groupRecord.map { groupRecord ->
      if (groupRecord.isV2Group) {
        ReviewUtil.getDuplicatedRecipients(groupRecord.id.requireV2()).map { it.recipient }
      } else {
        emptyList()
      }
    }

    _reviewState = Observable.combineLatest(_groupRecord, duplicates) { record, dupes ->
      if (dupes.isEmpty()) {
        ConversationGroupReviewState.EMPTY
      } else {
        ConversationGroupReviewState(record.id.requireV2(), dupes[0], dupes.size)
      }
    }.subscribeWithSubject(BehaviorSubject.create(), disposables)

    disposables += _groupRecord.subscribe { groupRecord ->
      _groupActiveState.onNext(ConversationGroupActiveState(groupRecord.isActive, groupRecord.isV2Group))
      _memberLevel.onNext(ConversationGroupMemberLevel(groupRecord.memberLevel(Recipient.self()), groupRecord.isAnnouncementGroup))
    }
  }

  override fun onCleared() {
    disposables.clear()
  }

  fun isNonAdminInAnnouncementGroup(): Boolean {
    val memberLevel = _memberLevel.value ?: return false
    return memberLevel.groupTableMemberLevel != GroupTable.MemberLevel.ADMINISTRATOR && memberLevel.isAnnouncementGroup
  }

  fun blockJoinRequests(recipient: Recipient): Single<GroupBlockJoinRequestResult> {
    return _groupRecord
      .firstOrError()
      .flatMap {
        groupManagementRepository.blockJoinRequests(it.id.requireV2(), recipient)
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun cancelJoinRequest(): Single<Result<Unit, GroupChangeFailureReason>> {
    return _groupRecord
      .firstOrError()
      .flatMap { group ->
        groupManagementRepository.cancelJoinRequest(group.id.requireV2())
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun onSuggestedMembersBannerDismissed() {
    _groupRecord
      .firstOrError()
      .flatMapCompletable { group ->
        groupManagementRepository.removeUnmigratedV1Members(group.id.requireV2())
      }
      .subscribe()
      .addTo(disposables)
  }

  class Factory(private val threadId: Long, private val recipientRepository: ConversationRecipientRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(ConversationGroupViewModel(threadId, recipientRepository = recipientRepository)) as T
    }
  }
}