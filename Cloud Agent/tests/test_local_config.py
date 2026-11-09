import pytest
from screenmind.config import Settings, local_settings


def test_local_configuration_and_environment_precedence(tmp_path, monkeypatch):
    for key in ("SCREENMIND_PROVIDER", "SCREENMIND_MODEL_BASE_URL", "SCREENMIND_MODEL", "SCREENMIND_MODEL_API_KEY", "SCREENMIND_MODEL_TIMEOUT"):
        monkeypatch.delenv(key, raising=False)
    path = tmp_path / ".env"
    path.write_text('# local\nSCREENMIND_PROVIDER=openai-compatible\nSCREENMIND_MODEL_BASE_URL="https://model.example/v1/"\nSCREENMIND_MODEL=local-model\nSCREENMIND_MODEL_API_KEY=private-test-value\nSCREENMIND_MODEL_TIMEOUT=65\n', encoding="utf-8-sig")
    monkeypatch.setenv("SCREENMIND_MODEL", "process-model")
    settings = Settings.from_env(path)
    assert settings.model == "process-model"
    assert settings.base_url == "https://model.example/v1"
    assert settings.model_key == "private-test-value"
    assert settings.model_timeout == 65
    assert "private-test-value" not in repr(settings)


def test_local_configuration_never_evaluates_values(tmp_path):
    path = tmp_path / ".env"
    path.write_text("SCREENMIND_MODEL_API_KEY='$(Do-Not-Execute)'\n", encoding="utf-8")
    assert local_settings(path)["SCREENMIND_MODEL_API_KEY"] == "$(Do-Not-Execute)"
    path.write_text("SCREENMIND_MODEL_API_KEY='private-test-value\n", encoding="utf-8")
    with pytest.raises(ValueError) as error:
        local_settings(path)
    assert "private-test-value" not in str(error.value)
