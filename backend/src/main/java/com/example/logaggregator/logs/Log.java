package com.example.logaggregator.logs;

import jakarta.persistence.*;

@Entity
@Table(name = "log_entries")
public class Log {
    @Id  // <-- ADD THIS
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
