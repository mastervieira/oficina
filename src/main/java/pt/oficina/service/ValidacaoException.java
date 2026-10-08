package pt.oficina.service;

/** Erro de regra de negócio ou de dados inválidos; a mensagem é mostrada ao utilizador. */
public class ValidacaoException extends RuntimeException {

    public ValidacaoException(String mensagem) {
        super(mensagem);
    }

    /** "O código é obrigatório." / "A descrição é obrigatória." (concorda com o artigo do nome do campo). */
    public static ValidacaoException obrigatorio(String campo) {
        boolean feminino = campo.startsWith("A ") || campo.startsWith("a ");
        return new ValidacaoException(campo + (feminino ? " é obrigatória." : " é obrigatório."));
    }
}
