# Traffic-Aware GraphHopper Docker Management

.PHONY: help build up down logs clean restart status shell health setup test

help:
	@echo "🚀 Traffic-Aware GraphHopper Docker Commands:"
	@echo ""
	@echo "📦 Setup & Build:"
	@echo "  setup    - First time setup (clone, build, start)"
	@echo "  build    - Build Docker image"
	@echo ""
	@echo "🚀 Runtime:"
	@echo "  up       - Start services"
	@echo "  down     - Stop services"
	@echo "  restart  - Restart services"
	@echo "  status   - Show status"
	@echo ""
	@echo "📊 Monitoring:"
	@echo "  logs     - Show logs"
	@echo "  health   - Health check"
	@echo "  test     - Test API endpoints"
	@echo ""
	@echo "🧹 Maintenance:"
	@echo "  clean    - Clean everything (containers, images, cache)"
	@echo "  shell    - Open shell in container"

setup: ## First time setup - build and start
	@echo "🚀 Setting up Traffic-Aware GraphHopper..."
	@echo "📦 Building Docker image (this may take 10-15 minutes)..."
	docker-compose build --no-cache
	@echo "🚀 Starting GraphHopper..."
	docker-compose up -d
	@echo "✅ Setup complete! Check logs with: make logs"
	@echo "🌐 API will be available at: http://localhost:8989"
	@echo "📊 Admin panel at: http://localhost:8990"

build:
	@echo "📦 Building GraphHopper Docker image..."
	docker-compose build --no-cache

up:
	@echo "🚀 Starting GraphHopper..."
	docker-compose up -d
	@echo "✅ GraphHopper started!"
	@echo "🌐 API: http://localhost:8989"
	@echo "📊 Admin: http://localhost:8990"

down:
	@echo "🛑 Stopping GraphHopper..."
	docker-compose down

logs:
	@echo "📋 Showing logs..."
	docker-compose logs -f

clean:
	@echo "🧹 Cleaning up containers and images..."
	docker-compose down -v --rmi all
	@echo "🗑️  Removing cache files..."
	rm -rf turkey-graph-cache/ || true
	@echo "✅ Cleanup complete!"

restart:
	@echo "🔄 Restarting GraphHopper..."
	docker-compose restart

status:
	@echo "📊 Container status:"
	docker-compose ps

shell:
	@echo "🐚 Opening shell in container..."
	docker-compose exec graphhopper-app /bin/bash

health:
	@echo "🏥 Health check..."
	curl -f http://localhost:8989/health || echo "❌ Health check failed"

test: ## Test API endpoints
	@echo "🧪 Testing API endpoints..."
	@echo "Testing basic route..."
	curl -s "http://localhost:8989/route?point=37.989355,32.523069&point=37.860192,32.547872&profile=car&locale=tr&points_encoded=false" | jq '.paths[0].distance' 2>/dev/null || echo "❌ API not ready yet"
	@echo "Testing EDS avoidance..."
	curl -s "http://localhost:8989/route?point=37.989355,32.523069&point=37.860192,32.547872&profile=car&avoid_eds_roads=true&locale=tr&points_encoded=false" | jq '.paths[0].distance' 2>/dev/null || echo "❌ EDS avoidance not working" 