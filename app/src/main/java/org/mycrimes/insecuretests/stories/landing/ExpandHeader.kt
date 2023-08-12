package org.mycrimes.insecuretests.stories.landing

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.mycrimes.insecuretests.R
import org.mycrimes.insecuretests.components.settings.DSLSettingsText
import org.mycrimes.insecuretests.components.settings.PreferenceModel
import org.mycrimes.insecuretests.util.adapter.mapping.LayoutFactory
import org.mycrimes.insecuretests.util.adapter.mapping.MappingAdapter
import org.mycrimes.insecuretests.util.adapter.mapping.MappingViewHolder

/**
 * Header that expands a section underneath it.
 */
object ExpandHeader {

  fun register(mappingAdapter: MappingAdapter) {
    mappingAdapter.registerFactory(Model::class.java, LayoutFactory(::ViewHolder, R.layout.expand_header))
  }

  class Model(
    override val title: DSLSettingsText,
    val isExpanded: Boolean,
    val onClick: (Boolean) -> Unit
  ) : PreferenceModel<Model>() {
    override fun areItemsTheSame(newItem: Model): Boolean = true

    override fun areContentsTheSame(newItem: Model): Boolean {
      return super.areContentsTheSame(newItem) &&
        isExpanded == newItem.isExpanded
    }
  }

  private class ViewHolder(itemView: View) : MappingViewHolder<Model>(itemView) {

    private val sectionHeader: TextView = itemView.findViewById(R.id.section_header)
    private val icon: ImageView = itemView.findViewById(R.id.icon)

    override fun bind(model: Model) {
      sectionHeader.text = model.title.resolve(context)
      icon.setImageResource(if (model.isExpanded) R.drawable.ic_chevron_up_24 else R.drawable.ic_chevron_down_24)
      icon.setColorFilter(ContextCompat.getColor(context, R.color.signal_icon_tint_primary))
      itemView.setOnClickListener { model.onClick(!model.isExpanded) }
    }
  }
}