import requests
import json
import random
import uuid
from datetime import datetime, timezone, timedelta

# Configuration
API_URL = "http://localhost:8080/api/v1/logs/batch"
TOTAL_LOGS = 10000
BATCH_SIZE = 500  # Send 500 logs per request to avoid timeouts

# Data Pools
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

def generate_log_entry():
    # Generate a random time within the last 24 hours
    time_offset = random.randint(0, 86400)
    timestamp = (datetime.now(timezone.utc) - timedelta(seconds=time_offset)).isoformat()

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
    print(f"🚀 Starting generation of {TOTAL_LOGS} logs...")

    batch = []
    total_sent = 0

    try:
        for i in range(TOTAL_LOGS):
            batch.append(generate_log_entry())

            # When batch is full, send it
            if len(batch) >= BATCH_SIZE:
                response = requests.post(API_URL, json=batch)

                if response.status_code == 202:
                    total_sent += len(batch)
                    print(f"✅ Sent batch {total_sent}/{TOTAL_LOGS}")
                else:
                    print(f"❌ Error sending batch: {response.status_code} - {response.text}")

                batch = [] # Clear batch

        # Send remaining logs
        if batch:
            response = requests.post(API_URL, json=batch)
            if response.status_code == 202:
                total_sent += len(batch)
                print(f"✅ Sent final batch. Total: {total_sent}")
            else:
                 print(f"❌ Error sending final batch: {response.status_code} - {response.text}")

    except requests.exceptions.ConnectionError:
        print("\n❌ Could not connect to the backend!")
        print(f"Make sure your Docker container is running at {API_URL}")

if __name__ == "__main__":
    main()