package moe.echo.bramblingnote.user;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;

@RestController
public class Controller {
    @Autowired
    private Repository repository;

    private final Environment environment;

    public Controller(Environment environment) {
        this.environment = environment;
    }

    private UserForReturn toUserForSession(UserEntity user) {
        UserForReturn u = new UserForReturn();
        u.setId(user.getId());
        u.setEmail(user.getEmail());
        u.setName(user.getName());
        u.setVerified(user.getVerified());
        u.setLastVerificationEmail(user.getLastVerificationEmail());
        return u;
    }

    private String newVerificationCode() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes);
    }

    private void sendVerificationEmail(String email, String verificationCode) throws MessagingException {
        String smtpEmail = environment.getProperty("smtp-email");
        String smtpPassword = environment.getProperty("smtp-password");
        String verificationEmailContent = environment.getProperty("verification-email-content");

        if (smtpEmail == null || smtpPassword == null) {
            throw new MessagingException("smtp-email and smtp-password from environment should not be null");
        }

        if (verificationEmailContent == null) {
            throw new MessagingException("verification-email-content from environment should not be null");
        }

        String smtpSslEnable = environment.getProperty("smtp-ssl-enable");
        String smtpStarttlsEnable = environment.getProperty("smtp-starttls-enable");

        if (!Objects.equals(smtpSslEnable, "true") && !Objects.equals(smtpSslEnable, "false")) {
            smtpSslEnable = "false";
        }

        if (!Objects.equals(smtpStarttlsEnable, "true") && !Objects.equals(smtpStarttlsEnable, "false")) {
            smtpStarttlsEnable = "false";
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.host", environment.getProperty("smtp-host"));
        properties.put("mail.smtp.port", environment.getProperty("smtp-port"));
        properties.put("mail.smtp.auth", environment.getProperty("smtp-auth"));
        properties.put("mail.smtp.ssl.enable", smtpSslEnable);
        properties.put("mail.smtp.starttls.enable", smtpStarttlsEnable);

        Session session = Session.getInstance(
                properties,
                new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(smtpEmail, smtpPassword);
                    }
                });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpEmail));
        message.setRecipients(Message.RecipientType.TO, new InternetAddress[]{new InternetAddress(email)});
        message.setSubject(environment.getProperty("verification-email-subject"));
        message.setText(String.format(verificationEmailContent, verificationCode));

        Transport.send(message);
    }

    @GetMapping("/health")
    public MessageJson health() {
        MessageJson message = new MessageJson();
        message.setMessage("ok");
        return message;
    }

    @PostMapping("/")
    public UserForReturn create(@RequestBody NewUser newUser, HttpSession session) {
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

        String verificationCode = newVerificationCode();
        user.setVerificationCode(verificationCode);
        UserEntity savedUser = repository.save(user);

        new Thread(() -> {
            try {
                sendVerificationEmail(email, verificationCode);
            } catch (MessagingException e) {
                System.out.println("Error from mail service:");
                e.printStackTrace();
            }
        }).start();

        UserForReturn ufs = toUserForSession(savedUser);
        session.setAttribute("user", ufs);
        return ufs;
    }

    @PostMapping("/verification")
    public UserForReturn verify(@RequestBody VerificationCodeRequest request, HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForReturn user) {
            return repository.findById(user.getId()).map(u -> {
                if (u.getVerificationCode().equals(request.getVerificationCode())) {
                    u.setVerified(true);
                    UserEntity savedUser = repository.save(u);
                    UserForReturn ufs = toUserForSession(savedUser);
                    session.setAttribute("user", ufs);
                    return ufs;
                }

                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(401),
                        "Verification code is invalid"
                );
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

    @DeleteMapping("/")
    public MessageJson delete(HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForReturn user) {
            return repository.findById(user.getId()).map(u -> {
                session.invalidate();
                repository.deleteById(u.getId());

                MessageJson message = new MessageJson();
                message.setMessage("ok");
                return message;
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
    public UserForReturn get(HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForReturn user) {
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

    @GetMapping("/verification-email")
    public MessageJson verifyEmail(HttpSession httpSession, Environment environment) {
        Object rawUser = httpSession.getAttribute("user");
        if (rawUser instanceof UserForReturn user) {
            repository.findById(user.getId()).map(u -> {
                String verificationCode = newVerificationCode();
                u.setVerificationCode(verificationCode);
                repository.save(u);

                // TODO: multithreading?
                try {
                    sendVerificationEmail(u.getEmail(), verificationCode);

                    MessageJson message = new MessageJson();
                    message.setMessage("ok");
                    return message;
                } catch (MessagingException e) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(500), e.getMessage());
                }
            }).orElseThrow(() -> new ResponseStatusException(
                    HttpStatusCode.valueOf(404),
                    "User `" + user.getId() + "` not found"
            ));
        }

        throw new ResponseStatusException(
                HttpStatusCode.valueOf(401), "You are not login yet"
        );
    }

    @PutMapping("/")
    public UserForReturn update(@RequestBody NewUser newUser, HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (rawUser instanceof UserForReturn user) {
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

                UserEntity savedUser = repository.save(u);
                UserForReturn ufs = toUserForSession(savedUser);
                session.setAttribute("user", ufs);
                return ufs;
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
