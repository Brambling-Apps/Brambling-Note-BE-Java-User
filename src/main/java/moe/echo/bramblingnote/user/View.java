package moe.echo.bramblingnote.user;

public class View {
    public interface Editable {}
    public interface EditOnly extends Editable {}
    public interface ViewOnly extends Editable {}
    public interface Internal extends EditOnly {}
}
