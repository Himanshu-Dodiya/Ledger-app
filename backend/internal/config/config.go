package config

import (
	"fmt"
	"os"
	"strconv"
)

type Config struct {
	Port               string
	DatabaseURL        string
	SupabaseURL        string // e.g. https://xxxx.supabase.co — used for JWKS (ES256/RS256 verification)
	SupabaseJWTSecret  string // optional; legacy HS256 shared secret
	TokenEncryptionKey string // 64 hex chars = 32 bytes
	GoogleClientID     string
	GoogleClientSecret string
	GeminiAPIKey       string // optional; LLM no-ops when empty
	GmailSyncIntervalM int    // minutes between Gmail poll runs per user
}

func Load() (*Config, error) {
	c := &Config{
		Port:               getenv("PORT", "8080"),
		DatabaseURL:        os.Getenv("DATABASE_URL"),
		SupabaseURL:        os.Getenv("SUPABASE_URL"),
		SupabaseJWTSecret:  os.Getenv("SUPABASE_JWT_SECRET"),
		TokenEncryptionKey: os.Getenv("TOKEN_ENCRYPTION_KEY"),
		GoogleClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
		GoogleClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
		GeminiAPIKey:       os.Getenv("GEMINI_API_KEY"),
		GmailSyncIntervalM: intenv("GMAIL_SYNC_INTERVAL_MINUTES", 15),
	}
	for _, pair := range [][]string{
		{"DATABASE_URL", c.DatabaseURL},
		{"SUPABASE_URL", c.SupabaseURL},
		{"TOKEN_ENCRYPTION_KEY", c.TokenEncryptionKey},
		{"GOOGLE_CLIENT_ID", c.GoogleClientID},
		{"GOOGLE_CLIENT_SECRET", c.GoogleClientSecret},
	} {
		if pair[1] == "" {
			return nil, fmt.Errorf("env %s is required", pair[0])
		}
	}
	return c, nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func intenv(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}
