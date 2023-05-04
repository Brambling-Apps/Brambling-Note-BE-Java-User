package moe.echo.bramblingnote.user;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class Controller {
    @Autowired
    private Repository repository;

    private UserForSession toUserForReturn(UserEntity user) {
        UserForSession u = new UserForSession();
        u.setId(user.getId());
        u.setEmail(user.getEmail());
        u.setName(user.getName());
        u.setVerified(user.getVerified());
        u.setLastVerifyEmail(user.getLastVerifyEmail());
        return u;
    }

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/")
    public UserEntity create(@RequestBody NewUser newUser, HttpSession session) {
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
        user.setName(name);

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

        session.setAttribute("user", toUserForReturn(user));
        return repository.save(user);
    }

    @DeleteMapping("/")
    public ResponseEntity<String> delete(HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForSession user) {
            return repository.findById(user.getId()).map(u -> {
                session.invalidate();
                repository.deleteById(u.getId());
                return ResponseEntity.ok().body("");
            }).orElseThrow(() -> {
                session.invalidate();
                return new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "User `" + user.getId() + "` was not found"
                );
            });
        }

        throw new ResponseStatusException(
                HttpStatusCode.valueOf(401), "You are not login yet"
        );
    }

    @GetMapping("/")
    public Object get(HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForSession user) {
            return user;
        }

        throw new ResponseStatusException(
                HttpStatusCode.valueOf(401), "You are not login yet"
        );
    }

    @GetMapping("/{email}")
    public UserEntity getByEmailAndPassword(@PathVariable String email, @RequestParam String password) {
        Argon2 argon2 = Argon2Factory.create();

        UserEntity user = repository.findByEmail(email);
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "User `" + email + "` was not found"
            );
        }

        if (password != null && argon2.verify(user.getPasswordHash(), password.getBytes())) {
            return user;
        } else {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(401), "Invalid password"
            );
        }
    }

    @PutMapping("/")
    public UserEntity update(@RequestBody NewUser newUser, HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForSession user) {
            return repository.findById(user.getId()).map(u -> {
                String email = newUser.getEmail();
                String name = newUser.getName();
                String password = newUser.getPassword();

                if (email != null && !email.equals(u.getEmail())) {
                    boolean emailExisted = repository.existsByEmail(email);

                    if (emailExisted) {
                        throw new ResponseStatusException(
                                HttpStatusCode.valueOf(400), "User " + email + " already exists"
                        );
                    }

                    u.setEmail(email);
                }
                if (name != null) {
                    u.setName(name);
                }
                if (password != null) {
                    Argon2 argon2 = Argon2Factory.create();
                    u.setPasswordHash(argon2.hash(
                            10, 65536, 1, password.getBytes()
                    ));
                }

                session.setAttribute("user", toUserForReturn(u));
                return repository.save(u);
            }).orElseThrow(() -> {
                session.invalidate();
                return new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "User `" + user.getId() + "` was not found"
                );
            });
        }

        throw new ResponseStatusException(
                HttpStatusCode.valueOf(401), "You are not login yet"
        );
    }
}
