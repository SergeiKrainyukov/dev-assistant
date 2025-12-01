#!/bin/bash
cd "$(dirname "$0")"
echo "🚀 Starting DevAssistant Web Server..."
echo "📡 Opening http://localhost:8080 in your browser..."
echo ""
./gradlew runWeb
