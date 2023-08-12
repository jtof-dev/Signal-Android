package org.mycrimes.insecuretests.conversation.drafts

import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.mycrimes.insecuretests.components.location.SignalPlace
import org.mycrimes.insecuretests.conversation.ConversationMessage
import org.mycrimes.insecuretests.database.DraftTable.Draft
import org.mycrimes.insecuretests.database.MentionUtil
import org.mycrimes.insecuretests.database.model.Mention
import org.mycrimes.insecuretests.database.model.MessageId
import org.mycrimes.insecuretests.database.model.databaseprotos.BodyRangeList
import org.mycrimes.insecuretests.mms.QuoteId
import org.mycrimes.insecuretests.recipients.Recipient
import org.mycrimes.insecuretests.recipients.RecipientId
import org.mycrimes.insecuretests.util.Base64
import org.mycrimes.insecuretests.util.rx.RxStore

/**
 * ViewModel responsible for holding Voice Note draft state. The intention is to allow
 * other pieces of draft state to be held here as well in the future, and to serve as a
 * management pattern going forward for drafts.
 */
class DraftViewModel @JvmOverloads constructor(
  threadId: Long = -1,
  private val repository: DraftRepository = DraftRepository()
) : ViewModel() {

  private val store = RxStore(DraftState(threadId = threadId))

  val state: Flowable<DraftState> = store.stateFlowable.observeOn(AndroidSchedulers.mainThread())

  val voiceNoteDraft: Draft?
    get() = store.state.voiceNoteDraft

  override fun onCleared() {
    store.dispose()
  }

  @Deprecated("Not needed for CFv2")
  fun setThreadId(threadId: Long) {
    store.update { it.copy(threadId = threadId) }
  }

  @Deprecated("Not needed for CFv2")
  fun setDistributionType(distributionType: Int) {
    store.update { it.copy(distributionType = distributionType) }
  }

  fun saveEphemeralVoiceNoteDraft(draft: Draft) {
    store.update { draftState ->
      saveDrafts(draftState.copy(voiceNoteDraft = draft))
    }
  }

  fun cancelEphemeralVoiceNoteDraft(draft: Draft) {
    repository.deleteVoiceNoteDraftData(draft)
  }

  fun deleteVoiceNoteDraft() {
    store.update {
      repository.deleteVoiceNoteDraftData(it.voiceNoteDraft)
      saveDrafts(it.copy(voiceNoteDraft = null))
    }
  }

  @Deprecated("Not needed for CFv2")
  fun onRecipientChanged(recipient: Recipient) {
    store.update { it.copy(recipientId = recipient.id) }
  }

  fun setMessageEditDraft(messageId: MessageId, text: String, mentions: List<Mention>, styleBodyRanges: BodyRangeList?) {
    store.update {
      val mentionRanges: BodyRangeList? = MentionUtil.mentionsToBodyRangeList(mentions)

      val bodyRanges: BodyRangeList? = if (styleBodyRanges == null) {
        mentionRanges
      } else if (mentionRanges == null) {
        styleBodyRanges
      } else {
        styleBodyRanges.toBuilder().addAllRanges(mentionRanges.rangesList).build()
      }

      saveDrafts(it.copy(textDraft = text.toTextDraft(), bodyRangesDraft = bodyRanges?.toDraft(), messageEditDraft = Draft(Draft.MESSAGE_EDIT, messageId.serialize())))
    }
  }

  fun deleteMessageEditDraft() {
    store.update {
      saveDrafts(it.copy(textDraft = null, bodyRangesDraft = null, messageEditDraft = null))
    }
  }

  fun setTextDraft(text: String, mentions: List<Mention>, styleBodyRanges: BodyRangeList?) {
    store.update {
      val mentionRanges: BodyRangeList? = MentionUtil.mentionsToBodyRangeList(mentions)

      val bodyRanges: BodyRangeList? = if (styleBodyRanges == null) {
        mentionRanges
      } else if (mentionRanges == null) {
        styleBodyRanges
      } else {
        styleBodyRanges.toBuilder().addAllRanges(mentionRanges.rangesList).build()
      }

      saveDrafts(it.copy(textDraft = text.toTextDraft(), bodyRangesDraft = bodyRanges?.toDraft()))
    }
  }

  fun setLocationDraft(place: SignalPlace) {
    store.update {
      saveDrafts(it.copy(locationDraft = Draft(Draft.LOCATION, place.serialize() ?: "")))
    }
  }

  fun clearLocationDraft() {
    store.update {
      saveDrafts(it.copy(locationDraft = null))
    }
  }

  fun setQuoteDraft(id: Long, author: RecipientId) {
    store.update {
      saveDrafts(it.copy(quoteDraft = Draft(Draft.QUOTE, QuoteId(id, author).serialize())))
    }
  }

  fun clearQuoteDraft() {
    store.update {
      saveDrafts(it.copy(quoteDraft = null))
    }
  }

  fun onSendComplete(threadId: Long = store.state.threadId) {
    repository.deleteVoiceNoteDraftData(store.state.voiceNoteDraft)
    store.update { saveDrafts(it.copyAndClearDrafts(threadId)) }
  }

  private fun saveDrafts(state: DraftState): DraftState {
    repository.saveDrafts(state.recipientId?.let { Recipient.resolved(it) }, state.threadId, state.distributionType, state.toDrafts())
    return state
  }

  @Deprecated("Not needed for CFv2")
  fun loadDrafts(threadId: Long): Single<DraftRepository.DatabaseDraft> {
    return repository
      .loadDrafts(threadId)
      .doOnSuccess { drafts ->
        store.update { saveDrafts(it.copyAndSetDrafts(threadId, drafts.drafts)) }
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  @Deprecated("Not needed for CFv2")
  fun loadDraftQuote(serialized: String): Maybe<ConversationMessage> {
    return repository.loadDraftQuote(serialized)
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
  }

  @Deprecated("Not needed for CFv2")
  fun loadDraftEditMessage(serialized: String): Maybe<ConversationMessage> {
    return repository.loadDraftMessageEdit(serialized)
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun loadShareOrDraftData(): Maybe<DraftRepository.ShareOrDraftData> {
    return repository.getShareOrDraftData()
      .doOnSuccess { (_, drafts) ->
        if (drafts != null) {
          store.update { saveDrafts(it.copyAndSetDrafts(drafts = drafts)) }
        }
      }
      .map { (data, _) -> data }
      .observeOn(AndroidSchedulers.mainThread())
  }
}

private fun String.toTextDraft(): Draft? {
  return if (isNotEmpty()) Draft(Draft.TEXT, this) else null
}

private fun BodyRangeList.toDraft(): Draft {
  return Draft(Draft.BODY_RANGES, Base64.encodeBytes(toByteArray()))
}