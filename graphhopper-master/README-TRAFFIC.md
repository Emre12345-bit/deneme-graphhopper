# Traffic-Aware GraphHopper Routing Engine

Bu proje, GraphHopper routing engine'ine EDS (Electronic Data System) ve Custom Areas (özel alanlar) kaçınma özelliklerini ekleyen geliştirilmiş bir versiyondur.

## 🚀 Özellikler

- **EDS Kaçınma**: EDS verilerine göre belirli yollardan kaçınma
- **Custom Areas Kaçınma**: Özel tanımlı alanlardan (yol çalışması, kazı vb.) kaçınma
- **3 Alternatif Rota**: Her sorgu için 3 farklı alternatif rota
- **Docker Desteği**: Kolay kurulum ve çalıştırma
- **Gerçek Zamanlı Veri**: 6 saatte bir otomatik veri güncelleme

## 📋 Gereksinimler

- Docker
- Docker Compose
- En az 4GB RAM
- En az 10GB disk alanı

## 🛠️ Kurulum

### 1. Projeyi Klonlayın
```bash
git clone <your-repo-url>
cd graphhopper-master
```

### 2. Docker ile Başlatın
```bash
# Build ve başlat
docker-compose up -d

# Logları takip et
docker-compose logs -f
```

### 3. Sistem Hazır Olana Kadar Bekleyin
İlk başlatmada sistem:
- Turkey OSM verilerini indirecek (~500MB)
- Graph cache'i oluşturacak (5-10 dakika)
- EDS ve Custom Areas verilerini çekecek

## 🌐 API Kullanımı

### Temel Rota Sorgusu
```bash
curl "http://localhost:8989/route?point=37.989355,32.523069&point=37.860192,32.547872&profile=car&locale=tr&points_encoded=false"
```

### EDS Kaçınma ile
```bash
curl "http://localhost:8989/route?point=37.989355,32.523069&point=37.860192,32.547872&profile=car&avoid_eds_roads=true&locale=tr&points_encoded=false"
```

### Custom Areas Kaçınma ile
```bash
curl "http://localhost:8989/route?point=37.989355,32.523069&point=37.860192,32.547872&profile=car&avoid_custom_areas=true&locale=tr&points_encoded=false"
```

### Her İkisini Birlikte
```bash
curl "http://localhost:8989/route?point=37.989355,32.523069&point=37.860192,32.547872&profile=car&avoid_eds_roads=true&avoid_custom_areas=true&locale=tr&points_encoded=false"
```

## ⚙️ Konfigürasyon

### config-example.yml
```yaml
graphhopper:
  # EDS API
  traffic:
    api:
      url: "https://api.metasyon.com/eds_data"
    auto_start: true

  # Custom Areas API
  custom_areas:
    enabled: true
    api:
      url: "http://20.199.11.220:9000/custom-areas"
      update_interval_hours: 6
```

## 📊 API Parametreleri

| Parametre | Tip | Açıklama |
|-----------|-----|----------|
| `avoid_eds_roads` | boolean | EDS yollarından kaçınma (true/false) |
| `avoid_custom_areas` | boolean | Özel alanlardan kaçınma (true/false) |
| `point` | lat,lng | Başlangıç ve bitiş noktaları |
| `profile` | string | Araç profili (car) |
| `locale` | string | Dil (tr) |

## 🔧 Geliştirme

### Yerel Geliştirme
```bash
# Maven ile build
mvn clean install -DskipTests

# Java ile çalıştır
java -jar web/target/graphhopper-web-*.jar server config-example.yml
```

### Docker Geliştirme
```bash
# Development build
docker-compose -f docker-compose.dev.yml up --build

# Production build
docker-compose up --build
```

## 📁 Proje Yapısı

```
graphhopper-master/
├── core/src/main/java/com/graphhopper/traffic/
│   ├── TrafficAwareCustomModelCreator.java
│   ├── CustomAreaDataService.java
│   ├── CustomAreaGeometryMatcher.java
│   └── TrafficAvoidanceWeighting.java
├── web-bundle/src/main/java/com/graphhopper/traffic/
│   └── TrafficAwareRequestTransformer.java
├── Dockerfile
├── docker-compose.yml
├── config-example.yml
└── README-TRAFFIC.md
```

## 🐛 Sorun Giderme

### Sistem Başlamıyor
```bash
# Logları kontrol et
docker-compose logs -f

# Container'ı yeniden başlat
docker-compose restart
```

### Cache Sorunları
```bash
# Cache'i temizle
docker-compose down
rm -rf turkey-graph-cache/
docker-compose up -d
```

### API Bağlantı Sorunları
- EDS API: `https://api.metasyon.com/eds_data`
- Custom Areas API: `http://20.199.11.220:9000/custom-areas`

## 📝 Lisans

Bu proje Apache License 2.0 altında lisanslanmıştır.

## 🤝 Katkıda Bulunma

1. Fork yapın
2. Feature branch oluşturun (`git checkout -b feature/amazing-feature`)
3. Commit yapın (`git commit -m 'Add amazing feature'`)
4. Push yapın (`git push origin feature/amazing-feature`)
5. Pull Request açın

## 📞 Destek

Sorunlar için GitHub Issues kullanın veya iletişime geçin. 