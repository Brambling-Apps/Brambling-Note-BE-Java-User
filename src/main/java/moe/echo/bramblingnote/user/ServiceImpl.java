package moe.echo.bramblingnote.user;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.UUID;

@Component
public class ServiceImpl {
    private final Repository repository;

    public ServiceImpl(Repository repository) {
        Assert.notNull(repository, "User repository cannot be null");
        this.repository = repository;
    }

    @CachePut(cacheNames = "users", key = "#user.id")
    public UserEntity save(UserEntity user) {
        Assert.notNull(user, "user cannot be null");
        return repository.save(user);
    }

    @CacheEvict("users")
    public void deleteById(UUID id) {
        Assert.notNull(id, "id cannot be null");
        this.repository.deleteById(id);
    }

    public boolean existsByEmail(String email) {
        Assert.notNull(email, "email cannot be null");
        return this.repository.existsByEmail(email);
    }

    @Cacheable("users")
    public UserEntity findById(UUID id) {
        Assert.notNull(id, "id cannot be null");
        return this.repository.findById(id).orElse(null);
    }

    public UserEntity findByEmail(String email) {
        Assert.notNull(email, "email cannot be null");
        return this.repository.findByEmail(email).orElse(null);
    }

    public boolean verificationCodeMatch(UUID id, String verificationCode) {
        return this.repository.findById(id)
                .map(user -> user.getVerificationCode().equals(verificationCode)).orElse(false);
    }

    public boolean passwordMatch(String email, String password) {
        Argon2 argon2 = Argon2Factory.create();

        return this.repository.findByEmail(email)
                .map(user -> password != null && argon2.verify(user.getPasswordHash(), password.getBytes()))
                .orElse(false);
    }
}
