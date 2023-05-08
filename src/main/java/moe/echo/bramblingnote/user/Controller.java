package moe.echo.bramblingnote.user;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpSession;
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
    private final ServiceImpl service;

    private final Environment environment;

    public Controller(ServiceImpl service, Environment environment) {
        this.service = service;
        this.environment = environment;
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

    private UserForReturn toExistedUserForReturn(HttpSession session) {
        Object rawUser = session.getAttribute("user");
        if (!(rawUser instanceof UserForReturn user)) {
            session.invalidate();
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(401), "You are not login yet"
            );
        }

        UserForReturn existedUser = service.findById(user.getId());
        if (existedUser == null) {
            session.invalidate();
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "User `" + user.getId() + "` was not found"
            );
        }

        return existedUser;
    }

    private UserEntity toUser(NewUser newUser) {
        UserEntity user = new UserEntity();
        user.setEmail(newUser.getEmail());
        user.setName(newUser.getName());

        if (newUser.getPassword() != null) {
            Argon2 argon2 = Argon2Factory.create();
            byte[] passwordByte = newUser.getPassword().getBytes();
            try {
                // Hash password
                user.setPasswordHash(argon2.hash(10, 65536, 1, passwordByte));
            } finally {
                // Wipe confidential data
                argon2.wipeArray(passwordByte);
            }
        }

        return user;
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

        boolean emailExisted = service.existsByEmail(email);

        if (emailExisted) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "User already exists: " + email
            );
        }

        UserEntity user = toUser(newUser);

        String verificationCode = newVerificationCode();
        user.setVerificationCode(verificationCode);
        UserForReturn savedUser = service.save(user);

        new Thread(() -> {
            try {
                sendVerificationEmail(email, verificationCode);
            } catch (MessagingException e) {
                System.out.println("Error from mail service:");
                e.printStackTrace();
            }
        }).start();

        session.setAttribute("user", savedUser);
        return savedUser;
    }

    @PostMapping("/verification")
    public UserForReturn verify(@RequestBody VerificationCodeRequest request, HttpSession session) {
        UserForReturn existedUser = toExistedUserForReturn(session);

        if (!service.verificationCodeMatch(existedUser.getId(), request.getVerificationCode())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(401),
                    "Verification code is invalid"
            );
        }

        return existedUser;
    }

    @DeleteMapping("/")
    public MessageJson delete(HttpSession session) {
        UserForReturn existedUser = toExistedUserForReturn(session);

        session.invalidate();
        service.deleteById(existedUser.getId());

        MessageJson message = new MessageJson();
        message.setMessage("ok");
        return message;
    }

    @GetMapping("/")
    public UserForReturn get(HttpSession session) {
        return toExistedUserForReturn(session);
    }

    @GetMapping("/{email}")
    public UserForReturn getByEmailAndPassword(@PathVariable String email, @RequestParam String password) {
        UserForReturn user = service.findByEmail(email);
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "User `" + email + "` was not found"
            );
        }

        if (!service.passwordMatch(email, password)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(401), "Invalid password"
            );
        }

        return user;
    }

    @GetMapping("/verification-email")
    public MessageJson verifyEmail(HttpSession httpSession) {
        UserForReturn user = toExistedUserForReturn(httpSession);
        UserEntity newUser = new UserEntity();
        newUser.setId(user.getId());
        String verificationCode = newVerificationCode();
        newUser.setVerificationCode(verificationCode);
        service.save(newUser);

        try {
            sendVerificationEmail(user.getEmail(), verificationCode);

            MessageJson message = new MessageJson();
            message.setMessage("ok");
            return message;
        } catch (MessagingException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(500), e.getMessage());
        }
    }

    @PutMapping("/")
    public UserForReturn update(@RequestBody NewUser newUser, HttpSession session) {
        UserForReturn existedUser = toExistedUserForReturn(session);
        UserEntity user = toUser(newUser);
        user.setId(existedUser.getId());

        String email = newUser.getEmail();
        if (email != null && !email.equals(existedUser.getEmail())) {
            if (service.existsByEmail(email)) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(400), "User " + email + " already exists"
                );
            }

            user.setEmail(email);
        }

        UserForReturn savedUser = service.save(user);
        session.setAttribute("user", savedUser);
        return savedUser;
    }
}
