# Makefile for notif-sys
#
# OS-agnostic 실행 진입점. Mac/Linux는 기본 내장 GNU make로 동작, Windows는 Git Bash /
# WSL / Chocolatey(`choco install make`) 환경에서 동작. Make가 없으면 README의
# "raw 명령" 섹션의 명령을 직접 실행하면 동일한 결과.
#
# 사전 요구: JDK 17 + JAVA_HOME, Docker 데몬 실행 중.

GRADLEW := ./gradlew

.PHONY: help up down build test run clean rebuild

help:
	@echo "Targets:"
	@echo "  make up       - Start PostgreSQL via docker compose"
	@echo "  make down     - Stop PostgreSQL"
	@echo "  make run      - Run the app on :8080 (requires 'make up' first)"
	@echo "  make test     - Run all tests (Testcontainers auto-spawns its own postgres)"
	@echo "  make build    - Compile + jar (skips tests)"
	@echo "  make clean    - Clean build artifacts"
	@echo "  make rebuild  - clean + build + test"

up:
	docker compose up -d

down:
	docker compose down

run:
	$(GRADLEW) bootRun

test:
	$(GRADLEW) test

build:
	$(GRADLEW) build -x test

clean:
	$(GRADLEW) clean

rebuild: clean build test