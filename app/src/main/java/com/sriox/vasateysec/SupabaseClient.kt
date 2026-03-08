package com.sriox.vasateysec

import android.content.Context
import io.github.jan.supabase.SupabaseClient as SupabaseClientType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

object SupabaseClient {
    // Supabase project credentials
    private const val SUPABASE_URL = "https://acgsmcxmesvsftzugeik.supabase.co"
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFjZ3NtY3htZXN2c2Z0enVnZWlrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjIyNzIzNTYsImV4cCI6MjA3Nzg0ODM1Nn0.EwiJajiscMqz1jHyyl-BDS4YIvc0nihBUn3m8pPUP1c"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    lateinit var client: SupabaseClientType
        private set
    
    fun initialize(context: Context) {
        client = createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            // Configure HTTP client with increased timeouts
            httpEngine = OkHttp.create {
                preconfigured = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            }
            
            install(Auth) {
                // Enable session persistence with SharedPreferences
                scheme = "app"
                host = "supabase.com"
                // Session will be automatically saved and restored
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
            install(Postgrest) {
                serializer = io.github.jan.supabase.serializer.KotlinXSerializer(json)
            }
            install(Storage)
            install(io.github.jan.supabase.realtime.Realtime)
        }
    }
}
