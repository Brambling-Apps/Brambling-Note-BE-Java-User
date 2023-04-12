package moe.echo.bramblingnote.user;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Data
public class EditedUser {
    private String email;

    private String name;

    private String password;

    private String newPassword;

    private Boolean verified;
}
