# GraphHopper Docker Setup

Basit Docker kurulumu için GraphHopper.

## 🚀 Hızlı Başlangıç

### Gereksinimler
- Docker 20.10+
- Docker Compose 2.0+
- En az 8GB RAM

### Kurulum
```bash
# 1. Build image
docker-compose build

# 2. Start service
docker-compose up -d

# 3. Check status
docker-compose ps
```

## 📁 Dosyalar

```
graphhopper-master/
├── Dockerfile              # Docker image
├── docker-compose.yml      # Services
├── config-example.yml     # Configuration
├── .dockerignore          # Ignore rules
├── Makefile               # Commands
├── turkey-latest.osm.pbf  # OSM data
├── turkey-graph-cache/    # Graph cache
└── logs/                  # Logs
```

## 🔧 Komutlar

```bash
# Makefile
make build     # Build image
make up        # Start service
make down      # Stop service
make logs      # Show logs
make status    # Show status
make health    # Health check

# Docker Compose
docker-compose build
docker-compose up -d
docker-compose down
docker-compose logs -f
```

## 📊 Monitoring

```bash
# Health check
curl http://localhost:8989/health

# Logs
docker-compose logs -f graphhopper
```

## 🛠️ Troubleshooting

### Memory Hatası
```bash
# Docker memory limitini artırın
docker-compose down
export DOCKER_MEMORY_LIMIT=8g
docker-compose up -d
```

### Port Çakışması
```bash
# docker-compose.yml'da portu değiştirin
ports:
  - "8988:8989"  # 8989 yerine 8988
```

### OSM Dosya Bulunamadı
```bash
# turkey-latest.osm.pbf dosyasının varlığını kontrol edin
ls -la turkey-latest.osm.pbf
``` 