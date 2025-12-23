import requests
import json
import random
import uuid
from datetime import datetime, timezone, timedelta

# Configuration
API_URL = "http://localhost:8080/api/v1/logs/batch"
TOTAL_LOGS = 10000
BATCH_SIZE = 500  # Send 500 logs per request to avoid timeouts

# Time Range Configuration
# Options: 'last_hour', 'last_6_hours', 'last_24_hours', 'last_week'
TIME_RANGE = 'last_24_hours'

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

def get_time_range_seconds():
    """Get the time range in seconds based on configuration"""
    ranges = {
        'last_hour': 3600,
        'last_6_hours': 21600,
        'last_24_hours': 86400,
        'last_week': 604800
    }
    return ranges.get(TIME_RANGE, 86400)

def generate_log_entry():
    # Generate a random time within the configured time range
    max_offset = get_time_range_seconds()
    time_offset = random.randint(0, max_offset)
    timestamp_dt = datetime.now(timezone.utc) - timedelta(seconds=time_offset)

    # Format timestamp explicitly for Java's Instant.parse()
    # Format: 2025-12-23T10:30:45.123Z
    timestamp = timestamp_dt.strftime('%Y-%m-%dT%H:%M:%S.%f')[:-3] + 'Z'

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
    print(f"📅 Time Range: {TIME_RANGE}")

    now = datetime.now(timezone.utc)
    start_time = now - timedelta(seconds=get_time_range_seconds())

    print(f"🕐 Current UTC time: {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"⏰ Generating logs from: {start_time.strftime('%Y-%m-%d %H:%M:%S')} to {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print()

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

    print(f"\n🎉 Complete! Sent {total_sent} logs total")

if __name__ == "__main__":
    main()