from collections import defaultdict, deque


class SessionStore:
    def __init__(self, max_turns: int = 20) -> None:
        self._sessions: dict[str, deque[str]] = defaultdict(
            lambda: deque(maxlen=max_turns)
        )
        self._pending: dict[str, dict] = {}

    def add(self, session_id: str, transcript: str) -> None:
        self._sessions[session_id].append(transcript)

    def history(self, session_id: str) -> list[str]:
        return list(self._sessions[session_id])

    def pending(self, session_id: str) -> dict | None:
        state = self._pending.get(session_id)
        if state is None:
            return None
        return {
            "intent": state["intent"],
            "slots": dict(state.get("slots") or {}),
            "missing": list(state.get("missing") or []),
        }

    def set_pending(self, session_id: str, intent: str, slots: dict, missing: list[str]) -> None:
        self._pending[session_id] = {
            "intent": intent,
            "slots": dict(slots),
            "missing": list(missing),
        }

    def clear_pending(self, session_id: str) -> None:
        self._pending.pop(session_id, None)
