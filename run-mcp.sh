#!/bin/bash
cd "$(dirname "$0")"
exec ./gradlew runMcp -q --console=plain
