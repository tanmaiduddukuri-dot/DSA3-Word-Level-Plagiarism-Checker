#!/usr/bin/env bash
# Starts the plagiarism-checker backend API and serves the frontend, then prints URLs.
set -e
cd "$(dirname "$0")"

BACKEND_PORT="${BACKEND_PORT:-8787}"
FRONTEND_PORT="${FRONTEND_PORT:-5500}"

echo "Starting backend API on port $BACKEND_PORT ..."
java -cp backend/classes com.plagcheck.api.Server "$BACKEND_PORT" &
BACKEND_PID=$!

echo "Starting frontend static server on port $FRONTEND_PORT ..."
cd frontend
python3 -m http.server "$FRONTEND_PORT" &
FRONTEND_PID=$!
cd ..

trap "echo; echo 'Stopping...'; kill $BACKEND_PID $FRONTEND_PID 2>/dev/null" EXIT INT TERM

sleep 1
echo
echo "Backend:  http://localhost:$BACKEND_PORT/api/health"
echo "Frontend: http://localhost:$FRONTEND_PORT"
echo
echo "Press Ctrl+C to stop both."
wait
