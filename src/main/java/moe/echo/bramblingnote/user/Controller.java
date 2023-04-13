package moe.echo.bramblingnote.user;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class Controller {
    @Autowired
    private Repository repository;

    @RequestMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/")
    public UserEntity add(@RequestBody NewUser newUser) {
        String email = newUser.getEmail();
        String name = newUser.getName();

        String invalidProperty = null;

        if (email == null) {
            invalidProperty = "Email";
        } else if (name == null) {
            invalidProperty = "Name";
        } else if (newUser.getPassword() == null) {
            invalidProperty = "Password";
        }

        if (invalidProperty != null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "Invalid " + invalidProperty + ": " + newUser
            );
        }

        boolean emailExisted = repository.existsByEmail(email);

        if (emailExisted) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "User already exists: " + email
            );
        }

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setName(newUser.getName());

        // https://github.com/phxql/argon2-jvm#usage
        Argon2 argon2 = Argon2Factory.create();
        byte[] passwordByte = newUser.getPassword().getBytes();
        try {
            // Hash password
            user.setPasswordHash(argon2.hash(10, 65536, 1, passwordByte));
        } finally {
            // Wipe confidential data
            argon2.wipeArray(passwordByte);
        }

        return repository.save(user);
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<String> delete(@PathVariable UUID uuid, @RequestBody Map<String, String> request) {
        // https://github.com/phxql/argon2-jvm#usage
        Argon2 argon2 = Argon2Factory.create();
        String password = request.get("password");

        if (password == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "Cannot resolve password: " + request
            );
        }

        return repository.findById(uuid).map(user -> {
            // Verify password
            if (argon2.verify(user.getPasswordHash(), password.getBytes())) {
                // Hash matches password
                repository.deleteById(uuid);
                return ResponseEntity.ok().body("");
            } else {
                // Hash doesn't match password
                return ResponseEntity.status(401).body("Invalid password");
            }
        }).orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404), "User `" + uuid + "` was not found"
        ));
    }

    @GetMapping("/{uuid}")
    public UserEntity get(@PathVariable UUID uuid, @RequestBody Map<String, String> request) {
        Argon2 argon2 = Argon2Factory.create();
        String password = request.get("password");

        if (password == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "Cannot resolve password: " + request
            );
        }

        return repository.findById(uuid).map(user -> {
            if (argon2.verify(user.getPasswordHash(), password.getBytes())) {
                return user;
            } else {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(401), "Invalid password"
                );
            }
        }).orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404), "User `" + uuid + "` was not found"
        ));
    }

    @PutMapping("/{uuid}")
    public UserEntity update(@PathVariable UUID uuid, @RequestBody EditedUser newUser) {
        Argon2 argon2 = Argon2Factory.create();

        return repository.findById(uuid).map(user -> {
            if (argon2.verify(user.getPasswordHash(), newUser.getPassword().getBytes())) {
                String email = newUser.getEmail();
                String name = newUser.getName();
                String password = newUser.getNewPassword();
                Boolean verified = newUser.getVerified();

                if (email != null && !email.equals(user.getEmail())) {
                    boolean emailExisted = repository.existsByEmail(email);

                    if (emailExisted) {
                        throw new ResponseStatusException(
                                HttpStatusCode.valueOf(400), "User already exists: " + email
                        );
                    }

                    user.setEmail(email);
                }
                if (name != null) {
                    user.setEmail(name);
                }
                if (password != null) {
                    user.setPasswordHash(argon2.hash(
                            10, 65536, 1, password.getBytes()
                    ));
                }
                if (verified != null) {
                    user.setVerified(verified);
                }

                return repository.save(user);
            } else {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(401), "Invalid password"
                );
            }
        }).orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404), "User `" + uuid + "` not found"
        ));
    }
}
