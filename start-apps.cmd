@echo off
chcp 65001 >nul
title Cloud Album Launcher

echo 正在启动后端...
start "Cloud Album Backend" cmd /k "cd /d D:\projects\Cloud-Album\backend && mvn spring-boot:run"

echo 正在启动前端...
start "Cloud Album Frontend" cmd /k "cd /d D:\projects\Cloud-Album\frontend && npm run dev"

echo 正在启动 AI 服务...
start "Cloud Album AI" cmd /k "cd /d D:\projects\Cloud-Album\ai-service && D:\pythonProject\RAGProject\venv\Scripts\python.exe run.py"

echo 三个服务启动命令已经执行。
exit