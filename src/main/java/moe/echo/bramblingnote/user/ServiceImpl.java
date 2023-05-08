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

    private UserForReturn toUserForReturn(UserEntity user) {
        UserForReturn u = new UserForReturn();
        u.setId(user.getId());
        u.setEmail(user.getEmail());
        u.setName(user.getName());
        u.setVerified(user.getVerified());
        u.setLastVerificationEmail(user.getLastVerificationEmail());
        return u;
    }

    private UserEntity mergeUsers(UserEntity newUser){
        Assert.notNull(newUser, "newUser cannot be null");
        Assert.notNull(newUser.getId(), "newUser.Id cannot be null");
        UserEntity existedUser = repository.findById(newUser.getId()).orElse(null);
        Assert.notNull(existedUser, "User `" + newUser.getId() + "` not found");

        UserEntity mergedUser = new UserEntity();
        mergedUser.setEmail(newUser.getEmail() == null ? existedUser.getEmail() : newUser.getEmail());
        mergedUser.setName(newUser.getName() == null ? existedUser.getName() : newUser.getName());
        mergedUser.setPasswordHash(
                newUser.getPasswordHash() == null ? existedUser.getPasswordHash() : newUser.getPasswordHash()
        );
        mergedUser.setVerified(newUser.getVerified() == null ? existedUser.getVerified() : newUser.getVerified());
        mergedUser.setVerificationCode(
                newUser.getVerificationCode() == null
                        ? existedUser.getVerificationCode()
                        : newUser.getVerificationCode()
        );
        mergedUser.setLastVerificationEmail(
                newUser.getLastVerificationEmail() == null
                        ? existedUser.getLastVerificationEmail()
                        : newUser.getLastVerificationEmail()
        );

        return mergedUser;
    }

    @CachePut(cacheNames = "users", key = "#user.id")
    public UserForReturn save(UserEntity user) {
        Assert.notNull(user, "user cannot be null");
        if (user.getId() == null) {
            return toUserForReturn(this.repository.save(user));
        }

        UserEntity existedUser = repository.findById(user.getId()).orElse(null);
        if (existedUser == null || existedUser.getId() == null) {
            return null;
        }

        return toUserForReturn(this.repository.save(mergeUsers(user)));
    }

    public boolean existsByEmail(String email) {
        Assert.notNull(email, "email cannot be null");
        return this.repository.existsByEmail(email);
    }

    @Cacheable("users")
    public UserForReturn findById(UUID id) {
        Assert.notNull(id, "id cannot be null");
        return this.repository.findById(id).map(this::toUserForReturn).orElse(null);
    }

    public UserForReturn findByEmail(String email) {
        Assert.notNull(email, "email cannot be null");
        return this.repository.findByEmail(email).map(this::toUserForReturn).orElse(null);
    }

    @CacheEvict("users")
    public void deleteById(UUID id) {
        Assert.notNull(id, "id cannot be null");
        this.repository.deleteById(id);
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
