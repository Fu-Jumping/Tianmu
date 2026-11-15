"""Offline ASR baseline; download model weights separately, never upload input audio.

Reference: https://github.com/SYSTRAN/faster-whisper
This measures one clip and preserves relative timestamps. It does not certify recognition accuracy.
"""
import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import pathlib
import time
import wave


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audio", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--model-path", required=True, type=pathlib.Path)
    parser.add_argument("--start-ms", type=int, default=0)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--glossary", default="", help="Generic subject vocabulary, not an expected transcript")
    args = parser.parse_args()
    if args.start_ms < 0 or not 1 <= args.threads <= 8 or len(args.glossary) > 500:
        parser.error("Invalid playback offset or CPU thread count")
    if not args.model_path.is_dir() or not args.audio.is_file():
        parser.error("Audio and pre-downloaded local model are required")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    from faster_whisper import WhisperModel
    import numpy as np

    # Consume the normalized PCM fixture directly; do not depend on changing PyAV decoder APIs.
    with wave.open(str(args.audio), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != 16000:
            parser.error("Benchmark input must be mono 16-bit PCM WAV at 16000 Hz")
        if not 0 < wav.getnframes() <= 120 * 16000:
            parser.error("Benchmark clips must contain at most 120 seconds of audio")
        audio = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768.0

    start = time.monotonic()
    model = WhisperModel(str(args.model_path), device="cpu", compute_type="int8",
                         cpu_threads=args.threads, local_files_only=True)
    loaded = time.monotonic()
    segments, info = model.transcribe(audio, language="zh", beam_size=5,
                                      word_timestamps=False, vad_filter=False,
                                      initial_prompt=args.glossary or None)
    values = []
    timing_errors = []
    for segment in segments:
        if not math.isfinite(segment.start) or not math.isfinite(segment.end):
            raise ValueError("ASR returned non-finite timestamps")
        if not 0 <= segment.start < segment.end <= info.duration:
            timing_errors.append(len(values))
        values.append({"start": segment.start, "end": segment.end, "text": segment.text,
                       "playbackStartMs": args.start_ms + math.floor(segment.start * 1000),
                       "playbackEndMs": args.start_ms + math.ceil(segment.end * 1000)})
    finished = time.monotonic()
    result = {
        "engine": "faster-whisper", "version": importlib.metadata.version("faster-whisper"),
        "model": args.model_path.name, "device": "cpu", "computeType": "int8",
        "threads": args.threads, "beamSize": 5, "audioSha256": hashlib.sha256(args.audio.read_bytes()).hexdigest(),
        "glossaryAssisted": bool(args.glossary), "glossary": args.glossary,
        "clipStartMs": args.start_ms, "durationSeconds": info.duration,
        "modelLoadSeconds": round(loaded-start, 3), "transcriptionSeconds": round(finished-loaded, 3),
        "text": "".join(value["text"] for value in values), "segments": values,
        "state": "INVALID_TIMING" if timing_errors else "AVAILABLE" if values else "NO_TRANSCRIPT",
        "timingErrorSegments": timing_errors, "accuracyReviewed": False,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf8")
    print(json.dumps({key: value for key, value in result.items() if key not in {"text", "segments"}}, ensure_ascii=True))
    if timing_errors:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
