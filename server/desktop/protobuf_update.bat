@echo off
rem Usage: protobuf_update.bat PINNED_DRIVER_CHECKOUT PROTOC_31_1
python "%~dp0../../scripts/generate_monaka_protobuf.py" --source "%~1" --protoc "%~2" --output "%~dp0../../build/generated-monaka-protobuf" --install "%~dp0src/main/java/dev/slimevr/desktop/platform/ProtobufMessages.java"
