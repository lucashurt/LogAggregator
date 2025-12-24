import requests
import json
import random
import uuid
import time
from datetime import datetime, timezone

# Configuration
API_URL = "http://localhost:8080/api/v1/logs/batch"
LOGS_PER_SECOND = 1000

# Data Pools (reused from your existing script)
SERVICES = ["auth-service", "payment-service", "notification-service", "user-service", "inventory-service", "shipping-service"]
LEVELS = ["INFO", "DEBUG", "WARNING", "ERROR"]
MESSAGES = [
    "User logged in successfully",
    "Payment processed with transaction id",
    "Critical database connection timeout error occurred",
    "Cache miss for user profile",
    "API request received from external gateway",
    "Authentication failed due to invalid token",
    "Transaction completed successfully",
    "Error processing request: NullPointerException",
    "Scheduled maintenance task started",
    "Dependency service unavailable: retry count exceeded"
]
REGIONS = ["us-east-1", "us-west-2", "eu-central-1", "ap-northeast-1"]

def generate_live_log_entry():
    # Use current time for live streaming
    now = datetime.now(timezone.utc)

    # Format: 2025-12-23T10:30:45.123Z
    timestamp = now.strftime('%Y-%m-%dT%H:%M:%S.%f')[:-3] + 'Z'

    return {
        "timestamp": timestamp,
        "serviceId": random.choice(SERVICES),
        "level": random.choice(LEVELS),
        "message": f"{random.choice(MESSAGES)} - {str(uuid.uuid4())[:8]}",
        "traceId": f"trace-{uuid.uuid4()}",
        "metadata": {
            "requestId": f"req-{random.randint(1000, 9999)}",
            "region": random.choice(REGIONS),
            "latency_ms": random.randint(5, 2000),
            "version": "v1.0.2"
        }
    }

def main():
    print(f"🚀 Starting continuous stream...")
    print(f"📡 Target: {API_URL}")
    print(f"⚡ Rate: {LOGS_PER_SECOND} logs/second")
    print("Press Ctrl+C to stop.\n")

    batch_count = 0

    try:
        while True:
            start_time = time.time()

            # 1. Generate Batch
            batch = [generate_live_log_entry() for _ in range(LOGS_PER_SECOND)]

            # 2. Send Batch
            try:
                response = requests.post(API_URL, json=batch)

                if response.status_code == 202:
                    batch_count += 1
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] ✅ Sent batch {batch_count} ({len(batch)} logs)")
                else:
                    print(f"❌ Error: {response.status_code} - {response.text}")

            except requests.exceptions.ConnectionError:
                print(f"❌ Connection failed. Is the server running at {API_URL}?")

            # 3. Wait for the remainder of the second
            elapsed = time.time() - start_time
            sleep_time = max(0, 1.0 - elapsed)
            time.sleep(sleep_time)

    except KeyboardInterrupt:
        print("\n🛑 Stream stopped by user.")

if __name__ == "__main__":
    main()