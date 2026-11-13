from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    postgres_user: str
    postgres_password: str
    postgres_db: str
    postgres_host: str
    postgres_port: int = 5432
    jwt_secret: str

    class Config:
        env_file = ".env"

settings = Settings()