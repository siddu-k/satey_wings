package com.sriox.vasateysec

import android.os.Bundle
import android.util.Log
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
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

class AiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 120_000
        }
        engine {
            config {
                connectTimeout(60, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                writeTimeout(120, TimeUnit.SECONDS)
            }
        }
    }
    
    private var openRouterApiKey: String = ""
    private var modelName: String = "arcee-ai/trinity-large-preview:free"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadSettings()
        
        binding.btnBack.setOnClickListener { finish() }
        
        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }

        addMessage("Hello! I am your Satey Safety Assistant. Ask me anything about staying safe.", isUser = false)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@AiChatActivity)
            adapter = chatAdapter
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        openRouterApiKey = prefs.getString("gemini_api_key", "")?.trim() ?: ""
        modelName = prefs.getString("gemini_model_name", "arcee-ai/trinity-large-preview:free")?.trim() ?: "arcee-ai/trinity-large-preview:free"

        if (openRouterApiKey.isEmpty()) {
            lifecycleScope.launch {
                try {
                    val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    if (currentUser != null) {
                        val userProfile = SupabaseClient.client.from("users")
                            .select { filter { eq("id", currentUser.id) } }
                            .decodeSingle<com.sriox.vasateysec.models.UserProfile>()
                        
                        userProfile.gemini_api_key?.let {
                            openRouterApiKey = it.trim()
                            prefs.edit().putString("gemini_api_key", openRouterApiKey).apply()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AiChat", "Cloud sync failed: ${e.message}")
                }
            }
        }
    }

    private fun sendMessage(text: String) {
        if (openRouterApiKey.isEmpty()) {
            addMessage("OpenRouter API key missing. Please go to Settings and enter your key.", isUser = false)
            return
        }

        addMessage(text, isUser = true)
        binding.etChatInput.text.clear()
        
        lifecycleScope.launch {
            try {
                // Thinking indicator
                val thinkingMsg = ChatMessage("...", isUser = false, isThinking = true)
                chatMessages.add(thinkingMsg)
                chatAdapter.notifyItemInserted(chatMessages.size - 1)
                binding.rvChat.scrollToPosition(chatMessages.size - 1)

                val response = callOpenRouterApi(text)
                
                if (chatMessages.isNotEmpty() && chatMessages.last().isThinking) {
                    chatMessages.removeAt(chatMessages.size - 1)
                    chatAdapter.notifyItemRemoved(chatMessages.size)
                }

                addMessage(response, isUser = false)
            } catch (e: Exception) {
                if (chatMessages.isNotEmpty() && chatMessages.last().isThinking) {
                    chatMessages.removeAt(chatMessages.size - 1)
                    chatAdapter.notifyItemRemoved(chatMessages.size)
                }
                addMessage("I'm having trouble connecting. Please check your internet or API key.", isUser = false)
            }
        }
    }

    private suspend fun callOpenRouterApi(prompt: String): String {
        return try {
            val url = "https://openrouter.ai/api/v1/chat/completions"
            
            val payload = buildJsonObject {
                put("model", modelName)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", "You are an expert safety assistant for the 'Satey' app. Be helpful and concise.")
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }

            val response = client.post(url) {
                setBody(payload.toString())
                header("Content-Type", "application/json")
                header("Authorization", "Bearer $openRouterApiKey")
            }

            val responseBody = response.bodyAsText()
            if (response.status.value == 200) {
                val json = Json.parseToJsonElement(responseBody).jsonObject
                val content = json["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                content ?: "No response from AI."
            } else {
                Log.e("AiChat", "OpenRouter Error: ${response.status.value} - $responseBody")
                "Error: ${response.status.value}. Please check your API key or model name in Settings."
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
            
            val params = holder.card.layoutParams as LinearLayout.LayoutParams
            if (msg.isUser) {
                holder.container.gravity = Gravity.END
                params.gravity = Gravity.END
                holder.card.setCardBackgroundColor(holder.itemView.context.getColor(R.color.violet))
                holder.text.setTextColor(holder.itemView.context.getColor(android.R.color.white))
            } else {
                holder.container.gravity = Gravity.START
                params.gravity = Gravity.START
                holder.card.setCardBackgroundColor(holder.itemView.context.getColor(R.color.card_elevated))
                holder.text.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            }
            holder.card.layoutParams = params
        }

        override fun getItemCount() = messages.size
    }
}
