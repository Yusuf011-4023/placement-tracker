@echo off
rem Starts Placement Tracker in the background (no console window).
rem Edit the jar name below if you build a different version.
cd /d "%~dp0"
start "" javaw -jar "target\placement-tracker-0.0.1-SNAPSHOT.jar"
