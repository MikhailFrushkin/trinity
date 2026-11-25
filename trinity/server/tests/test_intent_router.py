from app.intent_router import INTENT_PLACE, INTENT_PRINT, INTENT_UNKNOWN, route
from app.session_store import SessionStore


def test_should_keep_unknown_intent_for_unrelated_phrase() -> None:
    result = route("поставь таймер на пять минут")

    assert result["intent"] == INTENT_UNKNOWN
    assert result["actions"] == []
    assert result["slots"] == {}
    assert result["reply_text"] == "Принято. Действия пока не подключены."


def test_should_place_order_from_paired_digits_and_valid_cell() -> None:
    result = route("Ей тринити размести закак 65 65 54 на адрес 15")

    assert result["intent"] == INTENT_PLACE
    assert result["slots"] == {"order_id": "656554", "cell": "15"}
    assert result["missing"] == []


def test_should_not_treat_order_digits_as_cell() -> None:
    result = route("размести заказ 656554 на адрес 15")

    assert result["slots"]["order_id"] == "656554"
    assert result["slots"]["cell"] == "15"


def test_should_split_glued_order_and_valid_cell() -> None:
    result = route("размести 65655415")

    assert result["intent"] == INTENT_PLACE
    assert result["slots"]["order_id"] == "656554"
    assert result["slots"]["cell"] == "15"
    assert result["missing"] == []


def test_should_place_order_from_colloquial_templates() -> None:
    phrases = (
        "закинь 166655 на адрес 48",
        "кинь на адрес 9 заказ 166655",
        "положи заказ 166655 в ячейку 50",
        "закинь на ячейку 11 заказ 166655",
        "разместить 166 655 по адресу 13",
    )
    for phrase in phrases:
        result = route(phrase)
        assert result["intent"] == INTENT_PLACE, phrase
        assert result["slots"]["order_id"] == "166655", phrase
        assert result["slots"]["cell"] in {"48", "9", "50", "11", "13"}, phrase
        assert result["missing"] == [], phrase


def test_should_reject_unknown_cell_and_ask_again() -> None:
    result = route("размести заказ 166655 на адрес 12")

    assert result["intent"] == INTENT_PLACE
    assert result["slots"] == {"order_id": "166655"}
    assert result["missing"] == ["cell"]
    assert "12" in result["reply_text"]


def test_should_continue_place_dialog_when_cell_comes_next() -> None:
    store = SessionStore()
    first = route("размести заказ 656554", store=store, session_id="s1")
    second = route("пятнадцать", store=store, session_id="s1")

    assert first["missing"] == ["cell"]
    assert second["intent"] == INTENT_PLACE
    assert second["slots"] == {"order_id": "656554", "cell": "15"}
    assert second["missing"] == []


def test_should_continue_print_dialog_when_zone_comes_next() -> None:
    store = SessionStore()
    first = route("напечатай стикер заказа 166655", store=store, session_id="s1")
    second = route("склад", store=store, session_id="s1")

    assert first["missing"] == ["zone"]
    assert second["slots"] == {"order_id": "166655", "zone": "склад"}
    assert second["missing"] == []


def test_should_ask_for_order_when_digits_are_not_six() -> None:
    store = SessionStore()
    first = route("размести на адрес 15", store=store, session_id="s1")
    second = route("65 65 54", store=store, session_id="s1")

    assert first["slots"] == {"cell": "15"}
    assert first["missing"] == ["order_id"]
    assert second["slots"] == {"order_id": "656554", "cell": "15"}
    assert second["missing"] == []


def test_should_read_hyphenated_order_on_print_sticker() -> None:
    result = route("Эй тренки, распечатай стикер на заказ 1632-84.")

    assert result["intent"] == INTENT_PRINT
    assert result["slots"]["order_id"] == "163284"
    assert result["missing"] == ["zone"]


def test_should_read_split_and_plain_six_digit_orders() -> None:
    assert route("распечатай стикер на заказ 16-32-84")["slots"]["order_id"] == "163284"
    assert route("распечатай стикер на заказ 163284")["slots"]["order_id"] == "163284"


def test_should_print_sticker_for_warehouse_assembly() -> None:
    result = route("напечатай стикер на складскую сборку заказа 166655")

    assert result["intent"] == INTENT_PRINT
    assert result["slots"] == {"order_id": "166655", "zone": "склад"}
    assert result["missing"] == []


def test_should_map_hall_assembly_to_shop_zone() -> None:
    result = route("Эй, тринити, дай стикер на зальную сборку заказ 166655.")

    assert result["intent"] == INTENT_PRINT
    assert result["slots"] == {"order_id": "166655", "zone": "магазин"}


def test_should_print_sticker_from_zone_templates() -> None:
    cases = (
        ("распечатай стикер на магазин заказ 166655", "магазин"),
        ("этикетка на сборку сс 166655", "сс"),
        ("выдай стикер на эс эс заказ 166655", "сс"),
        ("напечатай наклейку на склад 166655", "склад"),
        ("стикер на зал заказ 166655", "магазин"),
    )
    for phrase, zone in cases:
        result = route(phrase)
        assert result["intent"] == INTENT_PRINT, phrase
        assert result["slots"]["order_id"] == "166655", phrase
        assert result["slots"]["zone"] == zone, phrase


def test_should_read_order_from_spoken_digits() -> None:
    result = route("кинь на адрес 15 заказ один шесть шесть шесть пять пять")

    assert result["intent"] == INTENT_PLACE
    assert result["slots"]["order_id"] == "166655"
    assert result["slots"]["cell"] == "15"


def test_should_cancel_pending_dialog() -> None:
    store = SessionStore()
    route("размести заказ 656554", store=store, session_id="s1")
    result = route("отмена", store=store, session_id="s1")

    assert result["intent"] == INTENT_UNKNOWN
    assert result["shutdown"] is False
    assert store.pending("s1") is None


def test_should_shutdown_on_trinity_stop() -> None:
    store = SessionStore()
    route("размести заказ 656554", store=store, session_id="s1")
    result = route("Тринити, стоп", store=store, session_id="s1")

    assert result["shutdown"] is True
    assert store.pending("s1") is None
