package moe.echo.bramblingnote.user;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
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
import java.util.Date;
import java.util.Objects;
import java.util.Properties;

@RestController
public class Controller {
    private final ServiceImpl service;

    private final Environment environment;

    private final HttpSession session;

    private final ObjectMapper objectMapper;

    private final UserMapper userMapper;

    public Controller(
            ServiceImpl service,
            Environment environment,
            HttpSession session,
            ObjectMapper objectMapper,
            UserMapper userMapper
    ) {
        this.service = service;
        this.environment = environment;
        this.session = session;
        this.objectMapper = objectMapper;
        this.userMapper = userMapper;
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

    private UserDto getUserFromSession() {
        Object rawUser = session.getAttribute("user");

        if (!(rawUser instanceof String userJson)) {
            session.invalidate();
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(401), "You are not login yet"
            );
        }

        ObjectMapper mapper = new ObjectMapper();

        try {
            return mapper.readValue(userJson, UserDto.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(500), e.getMessage()
            );
        }
    }

    private void saveToSession (UserDto user) {
        ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
        try {
            session.setAttribute("user", writer.writeValueAsString(user));
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(500), e.getMessage()
            );
        }
    }

    @GetMapping("/health")
    public MessageJson health() {
        MessageJson message = new MessageJson();
        message.setMessage("ok");
        return message;
    }

    @PostMapping("/")
    @JsonView(View.ViewOnly.class)
    public UserDto create(@RequestBody @JsonView(View.EditOnly.class) UserDto newUser) {
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

        String verificationCode = newVerificationCode();
        newUser.setVerificationCode(verificationCode);
        UserDto savedUser = userMapper.toUserDto(service.save(userMapper.toUser(newUser)));

        new Thread(() -> {
            try {
                sendVerificationEmail(email, verificationCode);
            } catch (MessagingException e) {
                System.out.println("Error from mail service:");
                e.printStackTrace();
            }
        }).start();

        saveToSession(savedUser);
        return savedUser;
    }

    @PostMapping("/verification")
    @JsonView(View.ViewOnly.class)
    public UserDto verify(@RequestBody VerificationCodeRequest request) {
        UserDto user = getUserFromSession();

        if (!service.verificationCodeMatch(user.getId(), request.getVerificationCode())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(401),
                    "Verification code is invalid"
            );
        }

        user.setVerified(true);
        user.setVerificationCode(null);

        return userMapper.toUserDto(service.save(userMapper.toUser(user)));
    }

    @DeleteMapping("/")
    @JsonView(View.ViewOnly.class)
    public MessageJson delete() {
        UserDto existedUser = getUserFromSession();

        session.invalidate();
        service.deleteById(existedUser.getId());

        MessageJson message = new MessageJson();
        message.setMessage("ok");
        return message;
    }

    @GetMapping("/")
    @JsonView(View.ViewOnly.class)
    public UserDto get() {
        return getUserFromSession();
    }

    @GetMapping("/{email}")
    @JsonView(View.ViewOnly.class)
    public UserDto getByEmailAndPassword(@PathVariable String email, @RequestParam String password) {
        UserDto user = userMapper.toUserDto(service.findByEmail(email));
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
    public MessageJson verifyEmail() {
        UserDto user = getUserFromSession();
        String verificationCode = newVerificationCode();
        user.setVerificationCode(verificationCode);
        service.save(userMapper.toUser(user));

        try {
            sendVerificationEmail(user.getEmail(), verificationCode);

            MessageJson message = new MessageJson();
            message.setMessage("ok");
            return message;
        } catch (MessagingException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(500), e.getMessage());
        }
    }

    @PatchMapping("/")
    @JsonView(View.ViewOnly.class)
    public UserDto patch(@RequestBody JsonPatch jsonPatch) {
        UserDto userFromSession = getUserFromSession();

        try {
            objectMapper.setConfig(objectMapper.getDeserializationConfig().withView(View.EditOnly.class));

            UserDto patchedUser = objectMapper.treeToValue(
                    jsonPatch.apply(objectMapper.convertValue(userFromSession, JsonNode.class))
                    , UserDto.class
            );
            UserEntity existedUser = service.findById(userFromSession.getId());

            if (existedUser == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "User " + userFromSession.getId() + "does not exist"
                );
            }

            UserEntity updatedUser = userMapper.toUser(patchedUser, existedUser);

            if (!Objects.equals(updatedUser.getEmail(), userFromSession.getEmail())) {
                String verificationCode = newVerificationCode();
                updatedUser.setVerified(false);
                updatedUser.setVerificationCode(verificationCode);
                updatedUser.setLastVerificationEmail(new Date());
                new Thread(() -> {
                    try {
                        sendVerificationEmail(updatedUser.getEmail(), verificationCode);
                    } catch (MessagingException e) {
                        System.out.println("Error from mail service: " + e.getMessage());
                    }
                }).start();
            }

            if (patchedUser.getPassword() != null) {
                // TODO
            }

            return userMapper.toUserDto(service.save(updatedUser));
        } catch (JsonPatchException | JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(500), "Failed to process request: " + e
            );
        }
    }
}
