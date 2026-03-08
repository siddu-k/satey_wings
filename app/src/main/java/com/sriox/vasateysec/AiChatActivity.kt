package com.sriox.vasateysec

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.sriox.vasateysec.databinding.ActivityAiChatBinding
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class AiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private val client = HttpClient(OkHttp)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        
        binding.btnBack.setOnClickListener { finish() }
        
        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }

        // Welcome message
        addMessage("Hello! I am your Satey Safety Assistant. How can I help you stay safe today?", isUser = false)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@AiChatActivity)
            adapter = chatAdapter
        }
    }

    private fun sendMessage(text: String) {
        addMessage(text, isUser = true)
        binding.etChatInput.text.clear()
        
        lifecycleScope.launch {
            try {
                // Get API Key from settings (shared preferences)
                val apiKey = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                    .getString("gemini_api_key", "") ?: ""

                if (apiKey.isEmpty()) {
                    addMessage("AI Assistant requires a Gemini API key. Please add one in Settings.", isUser = false)
                    return@launch
                }

                // Show "thinking" state
                val thinkingMsg = ChatMessage("Thinking...", isUser = false, isThinking = true)
                chatMessages.add(thinkingMsg)
                chatAdapter.notifyItemInserted(chatMessages.size - 1)
                binding.rvChat.scrollToPosition(chatMessages.size - 1)

                val response = callGeminiApi(text, apiKey)
                
                // Remove thinking message
                chatMessages.removeAt(chatMessages.size - 1)
                chatAdapter.notifyItemRemoved(chatMessages.size)

                addMessage(response, isUser = false)
            } catch (e: Exception) {
                addMessage("Sorry, I encountered an error connecting to my brain.", isUser = false)
            }
        }
    }

    private suspend fun callGeminiApi(prompt: String, apiKey: String): String {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            
            val payload = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", "You are a Safety Assistant for the 'Satey' app. Give concise, practical safety advice. User asks: $prompt")
                            }
                        }
                    }
                }
            }

            val response = client.post(url) {
                setBody(payload.toString())
                header("Content-Type", "application/json")
            }

            if (response.status.value == 200) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                json["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "No response from AI."
            } else {
                "Error: API returned ${response.status.value}"
            }
        } catch (e: Exception) {
            "Network error: ${e.message}"
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        chatMessages.add(ChatMessage(text, isUser))
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        binding.rvChat.scrollToPosition(chatMessages.size - 1)
    }

    data class ChatMessage(val text: String, val isUser: Boolean, val isThinking: Boolean = false)

    class ChatAdapter(private val messages: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val container: LinearLayout = view.findViewById(R.id.messageContainer)
            val card: MaterialCardView = view.findViewById(R.id.messageCard)
            val text: TextView = view.findViewById(R.id.tvMessageText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = messages[position]
            holder.text.text = msg.text
            
            if (msg.isUser) {
                holder.container.gravity = Gravity.END
                holder.card.setCardBackgroundColor(holder.itemView.context.getColor(R.color.violet))
                holder.text.setTextColor(holder.itemView.context.getColor(android.R.color.white))
            } else {
                holder.container.gravity = Gravity.START
                holder.card.setCardBackgroundColor(holder.itemView.context.getColor(R.color.card_elevated))
                holder.text.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            }
        }

        override fun getItemCount() = messages.size
    }
}
