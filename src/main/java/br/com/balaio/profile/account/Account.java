package br.com.balaio.profile.account;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id public UUID id;
    @Column(name = "name", nullable = false) public String name;
    @Column(nullable = false, unique = true) public String email;
    @Column(name = "password_hash", nullable = false) public String passwordHash;
    @Column(nullable = false) public String role;

    protected Account() {}
    public UUID getId() { return id; }
    public Account(String name, String email, String passwordHash, String role) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }
}
