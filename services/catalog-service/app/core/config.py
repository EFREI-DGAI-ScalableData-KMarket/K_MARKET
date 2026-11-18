from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    mongo_uri: str
    mongo_db: str = "kmarket"
    jwt_secret: str

    stock_url: str = "http://stock-service:8002/v1"
    auth_url: str = ""
    catalog_api_key: str = ""


settings = Settings()