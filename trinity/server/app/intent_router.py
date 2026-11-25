from __future__ import annotations

import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.session_store import SessionStore

STUB_REPLY = "Принято. Действия пока не подключены."
VALID_CELLS = ("9", "11", "13", "14", "15", "48", "50")
VALID_CELL_SET = frozenset(VALID_CELLS)
# CELL_HINT = "9, 11, 13, 14, 15, 48 или 50"
CELL_HINT = " "

INTENT_PLACE = "place_order"
INTENT_PRINT = "print_sticker"
INTENT_UNKNOWN = "unknown"

ZONE_WAREHOUSE = "склад"
ZONE_SHOP = "магазин"
ZONE_SS = "сс"

_DIGIT_WORDS = {
    "ноль": "0",
    "нуль": "0",
    "один": "1",
    "одна": "1",
    "раз": "1",
    "два": "2",
    "две": "2",
    "три": "3",
    "четыре": "4",
    "пять": "5",
    "шесть": "6",
    "семь": "7",
    "восемь": "8",
    "девять": "9",
}

_CELL_PHRASES = (
    ("сорок восемь", "48"),
    ("сороквосемь", "48"),
    ("пятьдесят", "50"),
    ("пятдесят", "50"),
    ("одиннадцать", "11"),
    ("тринадцать", "13"),
    ("четырнадцать", "14"),
    ("пятнадцать", "15"),
)
_CELL_WORDS = {
    "девять": "9",
    "одиннадцать": "11",
    "тринадцать": "13",
    "четырнадцать": "14",
    "пятнадцать": "15",
}

_PLACE_HINTS = (
    "размест",
    "закин",
    "заким",
    "закинь",
    "кинь",
    "кинуть",
    "кидай",
    "положи",
    "полож",
    "на адрес",
    "по адресу",
    "в ячейк",
    "на ячейк",
    "в ячейку",
    "ячейку",
)

_PRINT_HINTS = (
    "стикер",
    "наклейк",
    "этикетк",
    "печат",
    "распечат",
    "напечат",
    "дай стикер",
    "выдай стикер",
    "стикер на",
)

_CANCEL_HINTS = (
    "отмен",
    "хватит",
    "забудь",
    "не надо",
    "стоп",
    "замолчи",
    "выключ",
    "отключ",
    "закончи",
)
_PUNCT_RE = re.compile(r"[^\w\s]+", re.UNICODE)
_DIGIT_DASH_RE = re.compile(r"(?<=\d)[-—_](?=\d)")
_ORDER_DIGITS_RE = re.compile(r"заказ[ауе]?\s+((?:\d+\s*)+)", re.IGNORECASE)
_CELL_AFTER_RE = re.compile(
    r"(?:адрес[ауе]?\s*|ячейк[ауие]\s*|ячейку\s*)(\d{1,3})",
    re.IGNORECASE,
)


def route(
    transcript: str,
    store: SessionStore | None = None,
    session_id: str = "default",
) -> dict:
    text = _normalize(transcript)
    if _is_cancel(text):
        if store is not None:
            store.clear_pending(session_id)
        if _is_shutdown(text):
            return _payload(
                INTENT_UNKNOWN,
                {},
                [],
                "Выключаюсь.",
                shutdown=True,
            )
        return _payload(INTENT_UNKNOWN, {}, [], "Отменила. Скажите новую команду.")

    pending = store.pending(session_id) if store is not None else None
    if _is_print(text):
        result = _route_print(text, pending if pending and pending["intent"] == INTENT_PRINT else None)
    elif _is_place(text):
        result = _route_place(text, pending if pending and pending["intent"] == INTENT_PLACE else None)
    elif pending and pending["intent"] == INTENT_PRINT:
        result = _route_print(text, pending)
    elif pending and pending["intent"] == INTENT_PLACE:
        result = _route_place(text, pending)
    else:
        result = _payload(INTENT_UNKNOWN, {}, [], STUB_REPLY)

    if store is None:
        return result
    if result["intent"] == INTENT_UNKNOWN or not result["missing"]:
        store.clear_pending(session_id)
    else:
        store.set_pending(
            session_id,
            result["intent"],
            result["slots"],
            result["missing"],
        )
    return result


def _route_place(text: str, pending: dict | None) -> dict:
    slots = dict(pending["slots"]) if pending else {}
    order_id = _extract_order_id(text) or slots.get("order_id")
    cell, cell_error = _extract_cell(text)
    if cell:
        slots["cell"] = cell
    if order_id:
        slots["order_id"] = order_id

    missing: list[str] = []
    if not order_id:
        missing.append("order_id")
    if not slots.get("cell"):
        missing.append("cell")

    if cell_error and not cell:
        reply = f"{cell_error} Назовите адрес: {CELL_HINT}."
    elif not missing:
        reply = f"Размещаю заказ {slots['order_id']} в ячейку {slots['cell']}."
    elif "order_id" in missing and "cell" in missing:
        reply = f"Назовите шесть цифр заказа и адрес: {CELL_HINT}."
    elif "order_id" in missing:
        reply = "Номер заказа не распознан. Назовите шесть цифр заказа."
    else:
        reply = f"Заказ {slots['order_id']} поняла. Назовите адрес: {CELL_HINT}."
    return _payload(INTENT_PLACE, slots, missing, reply)


def _route_print(text: str, pending: dict | None) -> dict:
    slots = dict(pending["slots"]) if pending else {}
    order_id = _extract_order_id(text) or slots.get("order_id")
    zone = _extract_zone(text) or slots.get("zone")
    if order_id:
        slots["order_id"] = order_id
    if zone:
        slots["zone"] = zone

    missing: list[str] = []
    if not order_id:
        missing.append("order_id")
    if not zone:
        missing.append("zone")

    if not missing:
        reply = f"Печатаю стикер заказа {slots['order_id']}, зона {slots['zone']}."
    elif "order_id" in missing and "zone" in missing:
        reply = "Назовите зону сборки: магазин, склад или СС — и шесть цифр заказа."
    elif "order_id" in missing:
        reply = f"Зона {slots['zone']} поняла. Назовите шесть цифр заказа."
    else:
        reply = f"Заказ {slots['order_id']} поняла. Какая зона: магазин, склад или СС?"
    return _payload(INTENT_PRINT, slots, missing, reply)


def _payload(
    intent: str,
    slots: dict,
    missing: list[str],
    reply_text: str,
    shutdown: bool = False,
) -> dict:
    return {
        "intent": intent,
        "actions": [],
        "slots": slots,
        "missing": missing,
        "reply_text": reply_text,
        "shutdown": shutdown,
    }


def _normalize(transcript: str) -> str:
    text = (transcript or "").lower().replace("ё", "е")
    text = _DIGIT_DASH_RE.sub("", text)
    text = _PUNCT_RE.sub(" ", text)
    for phrase, digits in _CELL_PHRASES:
        text = text.replace(phrase, f" {digits} ")
    text = _replace_digit_words(text)
    text = text.replace("закак", "заказ")
    text = _glue_six_digit_groups(text)
    return re.sub(r"\s+", " ", text).strip()


def _glue_six_digit_groups(text: str) -> str:
    tokens = text.split()
    glued: list[str] = []
    index = 0
    while index < len(tokens):
        if not tokens[index].isdigit() or tokens[index] in VALID_CELL_SET:
            glued.append(tokens[index])
            index += 1
            continue
        combined = tokens[index]
        cursor = index + 1
        while cursor < len(tokens) and tokens[cursor].isdigit() and len(combined) < 6:
            combined += tokens[cursor]
            cursor += 1
        if len(combined) == 6:
            glued.append(combined)
            index = cursor
            continue
        glued.append(tokens[index])
        index += 1
    return " ".join(glued)


def _replace_digit_words(text: str) -> str:
    tokens = text.split()
    replaced: list[str] = []
    index = 0
    while index < len(tokens):
        chunk: list[str] = []
        cursor = index
        while cursor < len(tokens) and tokens[cursor] in _DIGIT_WORDS:
            chunk.append(_DIGIT_WORDS[tokens[cursor]])
            cursor += 1
        if len(chunk) >= 2:
            replaced.append("".join(chunk))
            index = cursor
            continue
        replaced.append(tokens[index])
        index += 1
    return " ".join(replaced)


def _is_print(text: str) -> bool:
    return any(hint in text for hint in _PRINT_HINTS)


def _is_place(text: str) -> bool:
    return any(hint in text for hint in _PLACE_HINTS)


def _is_cancel(text: str) -> bool:
    return any(hint in text for hint in _CANCEL_HINTS)


def _is_shutdown(text: str) -> bool:
    return _is_cancel(text) and ("тринити" in text or "trinity" in text)


def _extract_order_id(text: str) -> str | None:
    near_order = _ORDER_DIGITS_RE.search(text)
    if near_order:
        found = _six_from_chunks(near_order.group(1).split())
        if found:
            return found
    return _six_from_chunks([token for token in text.split() if token.isdigit()])


def _six_from_chunks(chunks: list[str]) -> str | None:
    digit_runs = [chunk for chunk in chunks if chunk.isdigit()]
    for token in digit_runs:
        if len(token) == 6:
            return token
    for token in digit_runs:
        if len(token) > 6:
            return token[:6]
    candidates: list[str] = []
    for start in range(len(digit_runs)):
        combined = ""
        for token in digit_runs[start:]:
            combined += token
            if len(combined) == 6:
                candidates.append(combined)
                break
            if len(combined) > 6:
                break
    if not candidates:
        return None
    preferred = [
        value
        for value in candidates
        if value[:2] not in VALID_CELL_SET and value[:1] not in VALID_CELL_SET
    ]
    return preferred[0] if preferred else candidates[0]


def _extract_cell(text: str) -> tuple[str | None, str | None]:
    match = _CELL_AFTER_RE.search(text)
    if match:
        value = match.group(1).lstrip("0") or "0"
        if value in VALID_CELL_SET:
            return value, None
        return None, f"Ячейки {value} нет."
    tokens = text.split()
    found: list[str] = []
    for token in tokens:
        mapped = _CELL_WORDS.get(token, token)
        if mapped in VALID_CELL_SET:
            found.append(mapped)
        elif mapped.isdigit() and len(mapped) > 6 and mapped[6:] in VALID_CELL_SET:
            found.append(mapped[6:])
    if len(found) == 1:
        return found[0], None
    if len(found) > 1:
        return found[-1], None
    return None, None


def _extract_zone(text: str) -> str | None:
    if re.search(r"\b(сс|с с|эс эс|эсэс|ss)\b", text):
        return ZONE_SS
    if re.search(r"\bсклад", text):
        return ZONE_WAREHOUSE
    if re.search(r"\bмагазин", text) or re.search(r"\bзальн", text) or re.search(r"\bзал\b", text):
        return ZONE_SHOP
    return None
