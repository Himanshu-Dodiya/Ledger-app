package httpx

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

// Envelope is the standard JSON response shape for all API endpoints.
type Envelope struct {
	Data  any    `json:"data,omitempty"`
	Error string `json:"error,omitempty"`
}

func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func OK(w http.ResponseWriter, data any) {
	JSON(w, http.StatusOK, Envelope{Data: data})
}

func Created(w http.ResponseWriter, data any) {
	JSON(w, http.StatusCreated, Envelope{Data: data})
}

func Error(w http.ResponseWriter, status int, msg string) {
	JSON(w, status, Envelope{Error: msg})
}

func BadRequest(w http.ResponseWriter, msg string)  { Error(w, http.StatusBadRequest, msg) }
func Unauthorized(w http.ResponseWriter)             { Error(w, http.StatusUnauthorized, "unauthorized") }

// UnauthorizedReason returns 401 with a specific reason in the body so the client
// (and logs) can distinguish missing-token vs bad-signature vs expired.
func UnauthorizedReason(w http.ResponseWriter, reason string) {
	Error(w, http.StatusUnauthorized, "unauthorized: "+reason)
}
func Forbidden(w http.ResponseWriter)                { Error(w, http.StatusForbidden, "forbidden") }
func NotFound(w http.ResponseWriter)                 { Error(w, http.StatusNotFound, "not found") }
func Conflict(w http.ResponseWriter, msg string)     { Error(w, http.StatusConflict, msg) }
func InternalError(w http.ResponseWriter)            { Error(w, http.StatusInternalServerError, "internal server error") }

func DecodeJSON(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}

// Logging wraps a handler and emits a structured log line per request.
func Logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rw := &responseWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rw, r)
		slog.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rw.status,
			"ms", time.Since(start).Milliseconds(),
		)
	})
}

type responseWriter struct {
	http.ResponseWriter
	status int
}

func (rw *responseWriter) WriteHeader(status int) {
	rw.status = status
	rw.ResponseWriter.WriteHeader(status)
}

// CORS allows cross-origin requests (web dashboard + dev tools).
func CORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
