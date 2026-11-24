from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    postgres_user: str          # dashboard_svc : lecture seule sur le schéma analytics
    postgres_password: str
    postgres_host: str = "postgres"
    postgres_port: int = 5432
    postgres_db: str = "kmarket"
    jwt_secret: str


settings = Settings()