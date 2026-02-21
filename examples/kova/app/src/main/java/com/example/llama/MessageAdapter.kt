package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

data class ChatMessage(
    val content: String,
    val isUser: Boolean
)

class MessageAdapter(
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var markwon: Markwon? = null

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    fun submitList(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun updateLastAssistantMessage(text: String): Int {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            messages[messages.size - 1] = ChatMessage(content = text, isUser = false)
            notifyItemChanged(messages.size - 1)
        } else {
            messages.add(ChatMessage(content = text, isUser = false))
            notifyItemInserted(messages.size - 1)
        }
        return messages.size - 1
    }

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // Markwon'u ilk ViewHolder oluşturulduğunda başlat
        if (markwon == null) {
            markwon = Markwon.create(parent.context)
        }
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER)
            UserMessageViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
        else
            AssistantMessageViewHolder(inflater.inflate(R.layout.item_message_assistant, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val textView = holder.itemView.findViewById<TextView>(R.id.msg_content)

        if (message.isUser) {
            // Kullanıcı mesajı: düz metin
            textView.text = message.content
        } else {
            // Asistan mesajı: markdown render
            markwon?.setMarkdown(textView, message.content)
                ?: run { textView.text = message.content }
        }

        holder.itemView.setOnLongClickListener {
            onLongClick(message.content)
            true
        }
    }

    override fun getItemCount() = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
