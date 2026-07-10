import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * Analisador de Cabeçalhos de Funções da Linguagem Java.
 *
 * Front-end de compilador escrito à mão (sem geradores) que faz, por fases,
 * a análise Léxica -> Sintáctica -> Semântica de um subconjunto de Java
 * centrado em cabeçalhos de funções:
 *
 *   - Funções de tipo int, double ou void (sem retorno).
 *   - Parâmetros formais (na declaração) com validação de tipo e nome.
 *   - Chamadas de funções com validação dos parâmetros actuais (reais)
 *     contra os parâmetros formais: número e compatibilidade de tipos.
 *   - Funções com blocos de instruções vazios (assinaladas).
 *
 * Segue a mesma lógica do projecto "analisador-switch-java": acumula todos
 * os erros de todas as fases (não pára no primeiro) e no fim imprime quatro
 * quadros: código fonte numerado, tabela de lexemas (tokens), tabela de
 * símbolos e lista de erros/avisos ordenada por linha.
 *
 * Tudo numa única classe, com classes internas para cada componente.
 *
 * Uso:
 *   javac AnalisadorCabecalhosFuncoes.java
 *   java  AnalisadorCabecalhosFuncoes [ficheiro.txt]
 * Sem argumento é analisado um exemplo embutido que exercita todos os erros.
 */
public class AnalisadorCabecalhosFuncoes {

    // ================================================================
    // TIPOS DA LINGUAGEM
    // ================================================================
    enum Tipo {
        INT, DOUBLE, VOID, ERRO;

        /**
         * Verifica se um valor do tipo {@code origem} pode ser passado/atribuído
         * onde se espera o tipo {@code destino}. Permite alargamento int -> double
         * (widening) mas proíbe estreitamento double -> int (narrowing).
         */
        static boolean compativel(Tipo destino, Tipo origem) {
            if (destino == ERRO || origem == ERRO) return true; // evita erros em cascata
            if (destino == origem) return true;
            return destino == DOUBLE && origem == INT;
        }
    }

    // ================================================================
    // FASE 1 — ANÁLISE LÉXICA
    // ================================================================
    enum ClasseToken {
        PALAVRA_CHAVE,   // int, double, void, return
        IDENTIFICADOR,   // nome de função, parâmetro ou variável
        NUMERO_INT,      // 10, 0, 255
        NUMERO_REAL,     // 3.14, 0.5
        ABRE_PAR, FECHA_PAR,
        ABRE_CHAVE, FECHA_CHAVE,
        VIRGULA, PONTO_VIRGULA,
        OP_ATRIB,        // =
        OP_ARIT,         // + - * /
        EOF,             // fim de ficheiro
        DESCONHECIDO     // caractere/símbolo inválido (erro léxico)
    }

    static final class Token {
        final ClasseToken classe;
        final String lexema;
        final int linha;
        final int coluna;

        Token(ClasseToken classe, String lexema, int linha, int coluna) {
            this.classe = classe;
            this.lexema = lexema;
            this.linha = linha;
            this.coluna = coluna;
        }

        @Override public String toString() {
            return "'" + lexema + "' [" + classe + "] (" + linha + ":" + coluna + ")";
        }
    }

    /** Palavras reservadas reconhecidas pelo analisador. */
    private static boolean ehPalavraChave(String s) {
        return s.equals("int") || s.equals("double") || s.equals("void") || s.equals("return");
    }

    /**
     * Lexer: percorre o texto caractere a caractere e produz a lista de tokens.
     * Caracteres inválidos geram tokens DESCONHECIDO e um erro léxico, mas a
     * análise continua para acumular o máximo de informação.
     */
    static final class Lexer {
        private final String origem;
        private final ColetorErros erros;
        private int pos = 0;
        private int linha = 1;
        private int coluna = 1;

        Lexer(String origem, ColetorErros erros) {
            this.origem = origem;
            this.erros = erros;
        }

        private char atual() { return pos < origem.length() ? origem.charAt(pos) : '\0'; }
        private char espreita(int k) {
            int i = pos + k;
            return i < origem.length() ? origem.charAt(i) : '\0';
        }

        private void avanca() {
            if (pos < origem.length()) {
                if (origem.charAt(pos) == '\n') { linha++; coluna = 1; }
                else { coluna++; }
                pos++;
            }
        }

        List<Token> tokenizar() {
            List<Token> tokens = new ArrayList<>();
            while (pos < origem.length()) {
                char c = atual();

                // Espaços em branco
                if (Character.isWhitespace(c)) { avanca(); continue; }

                // Comentários de linha // ...
                if (c == '/' && espreita(1) == '/') {
                    while (pos < origem.length() && atual() != '\n') avanca();
                    continue;
                }
                // Comentários de bloco /* ... */
                if (c == '/' && espreita(1) == '*') {
                    int lIni = linha, cIni = coluna;
                    avanca(); avanca();
                    boolean fechado = false;
                    while (pos < origem.length()) {
                        if (atual() == '*' && espreita(1) == '/') { avanca(); avanca(); fechado = true; break; }
                        avanca();
                    }
                    if (!fechado) {
                        erros.adicionar(Fase.LEXICA, lIni, cIni, "Comentário de bloco não terminado.");
                    }
                    continue;
                }

                int lIni = linha, cIni = coluna;

                // Identificadores e palavras-chave
                if (Character.isLetter(c) || c == '_') {
                    StringBuilder sb = new StringBuilder();
                    while (Character.isLetterOrDigit(atual()) || atual() == '_') {
                        sb.append(atual());
                        avanca();
                    }
                    String lex = sb.toString();
                    ClasseToken cl = ehPalavraChave(lex) ? ClasseToken.PALAVRA_CHAVE : ClasseToken.IDENTIFICADOR;
                    tokens.add(new Token(cl, lex, lIni, cIni));
                    continue;
                }

                // Números inteiros e reais
                if (Character.isDigit(c)) {
                    StringBuilder sb = new StringBuilder();
                    boolean real = false;
                    while (Character.isDigit(atual())) { sb.append(atual()); avanca(); }
                    if (atual() == '.') {
                        real = true;
                        sb.append(atual()); avanca();
                        if (!Character.isDigit(atual())) {
                            erros.adicionar(Fase.LEXICA, lIni, cIni,
                                    "Número real mal formado: '" + sb + "' (falta dígito após o ponto).");
                        }
                        while (Character.isDigit(atual())) { sb.append(atual()); avanca(); }
                    }
                    tokens.add(new Token(real ? ClasseToken.NUMERO_REAL : ClasseToken.NUMERO_INT,
                            sb.toString(), lIni, cIni));
                    continue;
                }

                // Símbolos
                switch (c) {
                    case '(': tokens.add(new Token(ClasseToken.ABRE_PAR, "(", lIni, cIni)); avanca(); break;
                    case ')': tokens.add(new Token(ClasseToken.FECHA_PAR, ")", lIni, cIni)); avanca(); break;
                    case '{': tokens.add(new Token(ClasseToken.ABRE_CHAVE, "{", lIni, cIni)); avanca(); break;
                    case '}': tokens.add(new Token(ClasseToken.FECHA_CHAVE, "}", lIni, cIni)); avanca(); break;
                    case ',': tokens.add(new Token(ClasseToken.VIRGULA, ",", lIni, cIni)); avanca(); break;
                    case ';': tokens.add(new Token(ClasseToken.PONTO_VIRGULA, ";", lIni, cIni)); avanca(); break;
                    case '=': tokens.add(new Token(ClasseToken.OP_ATRIB, "=", lIni, cIni)); avanca(); break;
                    case '+': case '-': case '*': case '/':
                        tokens.add(new Token(ClasseToken.OP_ARIT, String.valueOf(c), lIni, cIni)); avanca(); break;
                    default:
                        erros.adicionar(Fase.LEXICA, lIni, cIni,
                                "Caractere inválido: '" + c + "'.");
                        tokens.add(new Token(ClasseToken.DESCONHECIDO, String.valueOf(c), lIni, cIni));
                        avanca();
                }
            }
            tokens.add(new Token(ClasseToken.EOF, "<EOF>", linha, coluna));
            return tokens;
        }
    }

    // ================================================================
    // TABELA DE SÍMBOLOS
    // ================================================================
    static final class Parametro {
        final Tipo tipo;
        final String nome;
        Parametro(Tipo tipo, String nome) { this.tipo = tipo; this.nome = nome; }
    }

    static final class Funcao {
        final String nome;
        final Tipo tipoRetorno;
        final List<Parametro> parametros;
        final int linha;
        boolean blocoVazio;

        Funcao(String nome, Tipo tipoRetorno, List<Parametro> parametros, int linha) {
            this.nome = nome;
            this.tipoRetorno = tipoRetorno;
            this.parametros = parametros;
            this.linha = linha;
        }

        String assinatura() {
            StringBuilder sb = new StringBuilder();
            sb.append(tipoRetorno.name().toLowerCase()).append(' ').append(nome).append('(');
            for (int i = 0; i < parametros.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(parametros.get(i).tipo.name().toLowerCase()).append(' ').append(parametros.get(i).nome);
            }
            sb.append(')');
            return sb.toString();
        }
    }

    /** Tabela de símbolos das funções declaradas (insere e pesquisa). */
    static final class TabelaSimbolos {
        private final Map<String, Funcao> funcoes = new LinkedHashMap<>();

        boolean existe(String nome) { return funcoes.containsKey(nome); }
        Funcao obter(String nome) { return funcoes.get(nome); }
        void inserir(Funcao f) { funcoes.put(f.nome, f); }
        List<Funcao> todas() { return new ArrayList<>(funcoes.values()); }
    }

    // ================================================================
    // ERROS — colector unificado das três fases
    // ================================================================
    enum Fase { LEXICA, SINTATICA, SEMANTICA, AVISO }

    static final class Erro {
        final Fase fase;
        final int linha;
        final int coluna;
        final String mensagem;
        Erro(Fase fase, int linha, int coluna, String mensagem) {
            this.fase = fase; this.linha = linha; this.coluna = coluna; this.mensagem = mensagem;
        }
    }

    static final class ColetorErros {
        private final List<Erro> lista = new ArrayList<>();
        void adicionar(Fase fase, int linha, int coluna, String msg) {
            lista.add(new Erro(fase, linha, coluna, msg));
        }
        boolean temErros() {
            return lista.stream().anyMatch(e -> e.fase != Fase.AVISO);
        }
        List<Erro> ordenados() {
            List<Erro> copia = new ArrayList<>(lista);
            copia.sort((a, b) -> a.linha != b.linha ? Integer.compare(a.linha, b.linha)
                                                    : Integer.compare(a.coluna, b.coluna));
            return copia;
        }
    }

    // ================================================================
    // FASE 2 e 3 — ANÁLISE SINTÁCTICA + SEMÂNTICA
    // ================================================================
    /**
     * Parser descendente recursivo. Reconhece a gramática abaixo e, em
     * simultâneo, faz as verificações semânticas (declaração de funções,
     * parâmetros formais e validação das chamadas).
     *
     * Gramática (EBNF):
     *   programa      -> { funcao | chamadaTopo }
     *   funcao        -> tipoRet IDENT '(' formais ')' bloco
     *   tipoRet       -> 'int' | 'double' | 'void'
     *   formais       -> [ formal { ',' formal } ]
     *   formal        -> tipoParam IDENT
     *   tipoParam     -> 'int' | 'double'
     *   bloco         -> '{' { instrucao } '}'
     *   instrucao     -> declVar | atribuicao | retorno | chamada ';'
     *   declVar       -> tipoParam IDENT [ '=' expr ] ';'
     *   atribuicao    -> IDENT '=' expr ';'
     *   retorno       -> 'return' [ expr ] ';'
     *   chamada       -> IDENT '(' actuais ')'
     *   actuais       -> [ expr { ',' expr } ]
     *   expr          -> termo { ('+'|'-'|'*'|'/') termo }
     *   termo         -> NUMERO_INT | NUMERO_REAL | chamada | IDENT
     */
    static final class Parser {
        private final List<Token> tokens;
        private final ColetorErros erros;
        private final TabelaSimbolos tabela;
        private int pos = 0;

        // Contexto da função a ser analisada (para validar 'return').
        private Funcao funcaoAtual;
        // Variáveis locais visíveis (nome -> tipo), inclui parâmetros formais.
        private Map<String, Tipo> escopo;
        private boolean encontrouRetornoValido;

        Parser(List<Token> tokens, ColetorErros erros, TabelaSimbolos tabela) {
            this.tokens = tokens;
            this.erros = erros;
            this.tabela = tabela;
        }

        private Token atual() { return tokens.get(pos); }
        private Token espreita(int k) {
            int i = pos + k;
            return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
        }
        private boolean fim() { return atual().classe == ClasseToken.EOF; }
        private Token avanca() { Token t = atual(); if (!fim()) pos++; return t; }
        private boolean verifica(ClasseToken c) { return atual().classe == c; }
        private boolean verificaLexema(String lex) {
            return atual().classe == ClasseToken.PALAVRA_CHAVE && atual().lexema.equals(lex);
        }

        private Token consome(ClasseToken esperado, String descricao) {
            if (verifica(esperado)) return avanca();
            Token t = atual();
            erros.adicionar(Fase.SINTATICA, t.linha, t.coluna,
                    "Esperava " + descricao + " mas encontrou '" + t.lexema + "'.");
            return null;
        }

        // -------- Recuperação de erro: salta até um ponto de sincronização --------
        private void sincronizar() {
            while (!fim()) {
                ClasseToken c = atual().classe;
                if (c == ClasseToken.PONTO_VIRGULA) { avanca(); return; }
                if (c == ClasseToken.FECHA_CHAVE) return;
                if (ehInicioDeFuncaoOuDecl()) return;
                avanca();
            }
        }

        private boolean ehInicioDeFuncaoOuDecl() {
            return verificaLexema("int") || verificaLexema("double") || verificaLexema("void");
        }

        // ------------------------------ programa ------------------------------
        void analisarPrograma() {
            while (!fim()) {
                int posAntes = pos;
                if (ehInicioDeFuncaoOuDecl()) {
                    analisarFuncao();
                } else if (verifica(ClasseToken.IDENTIFICADOR)) {
                    // Chamada de função ao nível de topo, terminada por ';'
                    analisarChamadaComoInstrucao(new java.util.HashMap<>());
                } else {
                    Token t = atual();
                    erros.adicionar(Fase.SINTATICA, t.linha, t.coluna,
                            "Elemento inesperado no programa: '" + t.lexema + "'. "
                            + "Esperava uma declaração de função ou uma chamada.");
                    avanca();
                }
                if (pos == posAntes) avanca(); // garantia contra ciclo infinito
            }
        }

        // ------------------------------ funcao ------------------------------
        private void analisarFuncao() {
            Token tTipo = avanca(); // int | double | void
            Tipo tipoRet = tipoDe(tTipo.lexema);

            Token nome = consome(ClasseToken.IDENTIFICADOR, "o nome da função");
            String nomeFuncao = nome != null ? nome.lexema : "<sem-nome>";
            int linhaFuncao = tTipo.linha;

            // Parâmetros formais
            consome(ClasseToken.ABRE_PAR, "'(' depois do nome da função");
            List<Parametro> formais = analisarParametrosFormais();
            consome(ClasseToken.FECHA_PAR, "')' a fechar os parâmetros formais");

            Funcao funcao = new Funcao(nomeFuncao, tipoRet, formais, linhaFuncao);

            // Semântica: função repetida?
            if (nome != null) {
                if (tabela.existe(nomeFuncao)) {
                    erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                            "Função '" + nomeFuncao + "' já foi declarada anteriormente.");
                } else {
                    tabela.inserir(funcao);
                }
            }

            // Preparar escopo com os parâmetros formais
            Map<String, Tipo> escopoLocal = new java.util.HashMap<>();
            for (Parametro p : formais) {
                if (escopoLocal.containsKey(p.nome)) {
                    erros.adicionar(Fase.SEMANTICA, linhaFuncao, 1,
                            "Parâmetro formal '" + p.nome + "' repetido na função '" + nomeFuncao + "'.");
                } else {
                    escopoLocal.put(p.nome, p.tipo);
                }
            }

            // Analisar o corpo
            Funcao anteriorFuncao = funcaoAtual;
            Map<String, Tipo> anteriorEscopo = escopo;
            boolean anteriorRetorno = encontrouRetornoValido;
            funcaoAtual = funcao;
            escopo = escopoLocal;
            encontrouRetornoValido = false;

            int instrucoes = analisarBloco();
            funcao.blocoVazio = instrucoes == 0;

            if (funcao.blocoVazio) {
                erros.adicionar(Fase.AVISO, linhaFuncao, 1,
                        "Função '" + nomeFuncao + "' tem bloco de instruções vazio.");
            }
            // Funções com retorno declarado (int/double) devem retornar valor.
            if ((tipoRet == Tipo.INT || tipoRet == Tipo.DOUBLE) && !encontrouRetornoValido && !funcao.blocoVazio) {
                erros.adicionar(Fase.SEMANTICA, linhaFuncao, 1,
                        "Função '" + nomeFuncao + "' do tipo " + tipoRet.name().toLowerCase()
                        + " não tem instrução 'return' com valor.");
            }

            funcaoAtual = anteriorFuncao;
            escopo = anteriorEscopo;
            encontrouRetornoValido = anteriorRetorno;
        }

        private List<Parametro> analisarParametrosFormais() {
            List<Parametro> formais = new ArrayList<>();
            if (verifica(ClasseToken.FECHA_PAR)) return formais; // lista vazia

            do {
                Token tTipo = atual();
                Tipo tipoParam;
                if (verificaLexema("int") || verificaLexema("double")) {
                    avanca();
                    tipoParam = tipoDe(tTipo.lexema);
                } else if (verificaLexema("void")) {
                    avanca();
                    erros.adicionar(Fase.SEMANTICA, tTipo.linha, tTipo.coluna,
                            "'void' não é um tipo válido para parâmetro formal.");
                    tipoParam = Tipo.ERRO;
                } else {
                    erros.adicionar(Fase.SINTATICA, tTipo.linha, tTipo.coluna,
                            "Esperava o tipo do parâmetro (int/double) mas encontrou '" + tTipo.lexema + "'.");
                    tipoParam = Tipo.ERRO;
                }

                Token nome = consome(ClasseToken.IDENTIFICADOR, "o nome do parâmetro formal");
                String nomeParam = nome != null ? nome.lexema : "<sem-nome>";
                formais.add(new Parametro(tipoParam, nomeParam));
            } while (verifica(ClasseToken.VIRGULA) && avanca() != null);

            return formais;
        }

        // ------------------------------ bloco ------------------------------
        /** Analisa um bloco '{ ... }' e devolve o número de instruções que contém. */
        private int analisarBloco() {
            int contador = 0;
            if (!verifica(ClasseToken.ABRE_CHAVE)) {
                consome(ClasseToken.ABRE_CHAVE, "'{' a abrir o corpo da função");
                return contador;
            }
            avanca(); // {
            while (!verifica(ClasseToken.FECHA_CHAVE) && !fim()) {
                int posAntes = pos;
                boolean analisou = analisarInstrucao();
                if (analisou) contador++;
                if (pos == posAntes) { sincronizar(); }
            }
            consome(ClasseToken.FECHA_CHAVE, "'}' a fechar o corpo da função");
            return contador;
        }

        // ------------------------------ instrucao ------------------------------
        private boolean analisarInstrucao() {
            // Declaração de variável local: tipoParam IDENT [= expr] ;
            if (verificaLexema("int") || verificaLexema("double")) {
                analisarDeclaracaoVariavel();
                return true;
            }
            if (verificaLexema("void")) {
                Token t = atual();
                erros.adicionar(Fase.SEMANTICA, t.linha, t.coluna,
                        "'void' não pode ser usado como tipo de variável local.");
                avanca();
                sincronizar();
                return true;
            }
            // return [expr] ;
            if (verificaLexema("return")) {
                analisarRetorno();
                return true;
            }
            // IDENT ... -> chamada ou atribuição
            if (verifica(ClasseToken.IDENTIFICADOR)) {
                if (espreita(1).classe == ClasseToken.ABRE_PAR) {
                    analisarChamadaComoInstrucao(escopo);
                } else {
                    analisarAtribuicao();
                }
                return true;
            }
            // Ponto e vírgula solto = instrução vazia (tolerada)
            if (verifica(ClasseToken.PONTO_VIRGULA)) { avanca(); return false; }

            Token t = atual();
            erros.adicionar(Fase.SINTATICA, t.linha, t.coluna,
                    "Instrução inválida a começar em '" + t.lexema + "'.");
            return false;
        }

        private void analisarDeclaracaoVariavel() {
            Token tTipo = avanca();
            Tipo tipo = tipoDe(tTipo.lexema);
            Token nome = consome(ClasseToken.IDENTIFICADOR, "o nome da variável");
            if (nome != null) {
                if (escopo.containsKey(nome.lexema)) {
                    erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                            "Variável '" + nome.lexema + "' já declarada neste escopo.");
                } else {
                    escopo.put(nome.lexema, tipo);
                }
            }
            if (verifica(ClasseToken.OP_ATRIB)) {
                avanca();
                Tipo tExpr = analisarExpressao();
                if (nome != null && !Tipo.compativel(tipo, tExpr)) {
                    erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                            "Não é possível atribuir valor " + tExpr.name().toLowerCase()
                            + " à variável '" + nome.lexema + "' do tipo " + tipo.name().toLowerCase() + ".");
                }
            }
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim da declaração");
        }

        private void analisarAtribuicao() {
            Token nome = avanca(); // IDENT
            Tipo tipoVar = escopo.get(nome.lexema);
            if (tipoVar == null) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Variável '" + nome.lexema + "' não foi declarada.");
                tipoVar = Tipo.ERRO;
            }
            consome(ClasseToken.OP_ATRIB, "'=' na atribuição");
            Tipo tExpr = analisarExpressao();
            if (!Tipo.compativel(tipoVar, tExpr)) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Não é possível atribuir valor " + tExpr.name().toLowerCase()
                        + " à variável '" + nome.lexema + "' do tipo " + tipoVar.name().toLowerCase() + ".");
            }
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim da atribuição");
        }

        private void analisarRetorno() {
            Token tReturn = avanca(); // return
            if (verifica(ClasseToken.PONTO_VIRGULA)) {
                avanca();
                // 'return;' sem valor
                if (funcaoAtual != null && funcaoAtual.tipoRetorno != Tipo.VOID) {
                    erros.adicionar(Fase.SEMANTICA, tReturn.linha, tReturn.coluna,
                            "Função '" + funcaoAtual.nome + "' do tipo "
                            + funcaoAtual.tipoRetorno.name().toLowerCase()
                            + " deve retornar um valor.");
                }
                return;
            }
            Tipo tExpr = analisarExpressao();
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim do 'return'");
            if (funcaoAtual != null) {
                if (funcaoAtual.tipoRetorno == Tipo.VOID) {
                    erros.adicionar(Fase.SEMANTICA, tReturn.linha, tReturn.coluna,
                            "Função '" + funcaoAtual.nome + "' é void (sem retorno) e não pode retornar valor.");
                } else if (!Tipo.compativel(funcaoAtual.tipoRetorno, tExpr)) {
                    erros.adicionar(Fase.SEMANTICA, tReturn.linha, tReturn.coluna,
                            "Retorno de tipo " + tExpr.name().toLowerCase() + " incompatível com o tipo "
                            + funcaoAtual.tipoRetorno.name().toLowerCase() + " da função '" + funcaoAtual.nome + "'.");
                } else {
                    encontrouRetornoValido = true;
                }
            }
        }

        // ------------------------------ chamada ------------------------------
        private void analisarChamadaComoInstrucao(Map<String, Tipo> escopoLocal) {
            analisarChamada(escopoLocal);
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim da chamada");
        }

        /** Analisa uma chamada IDENT( actuais ) e devolve o tipo de retorno da função. */
        private Tipo analisarChamada(Map<String, Tipo> escopoLocal) {
            Token nome = avanca(); // IDENT
            consome(ClasseToken.ABRE_PAR, "'(' na chamada de função");

            List<Tipo> actuais = new ArrayList<>();
            if (!verifica(ClasseToken.FECHA_PAR)) {
                do {
                    actuais.add(analisarExpressao());
                } while (verifica(ClasseToken.VIRGULA) && avanca() != null);
            }
            consome(ClasseToken.FECHA_PAR, "')' a fechar a chamada de função");

            // Semântica: função existe?
            Funcao f = tabela.obter(nome.lexema);
            if (f == null) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Chamada à função '" + nome.lexema + "' que não foi declarada.");
                return Tipo.ERRO;
            }

            // Número de parâmetros
            if (actuais.size() != f.parametros.size()) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Função '" + nome.lexema + "' espera " + f.parametros.size()
                        + " parâmetro(s) mas recebeu " + actuais.size() + ".");
            } else {
                // Compatibilidade de tipos parâmetro a parâmetro
                for (int i = 0; i < actuais.size(); i++) {
                    Tipo formal = f.parametros.get(i).tipo;
                    Tipo actual = actuais.get(i);
                    if (!Tipo.compativel(formal, actual)) {
                        erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                                "Parâmetro " + (i + 1) + " da chamada a '" + nome.lexema
                                + "' é " + actual.name().toLowerCase() + " mas o parâmetro formal '"
                                + f.parametros.get(i).nome + "' é " + formal.name().toLowerCase() + ".");
                    }
                }
            }
            return f.tipoRetorno;
        }

        // ------------------------------ expressao ------------------------------
        private Tipo analisarExpressao() {
            Tipo t = analisarTermo();
            while (verifica(ClasseToken.OP_ARIT)) {
                avanca();
                Tipo t2 = analisarTermo();
                t = combinarNumerico(t, t2);
            }
            return t;
        }

        private Tipo analisarTermo() {
            Token t = atual();
            switch (t.classe) {
                case NUMERO_INT:  avanca(); return Tipo.INT;
                case NUMERO_REAL: avanca(); return Tipo.DOUBLE;
                case ABRE_PAR:
                    avanca();
                    Tipo interno = analisarExpressao();
                    consome(ClasseToken.FECHA_PAR, "')' a fechar a subexpressão");
                    return interno;
                case IDENTIFICADOR:
                    if (espreita(1).classe == ClasseToken.ABRE_PAR) {
                        Tipo tr = analisarChamada(escopo != null ? escopo : new java.util.HashMap<>());
                        if (tr == Tipo.VOID) {
                            erros.adicionar(Fase.SEMANTICA, t.linha, t.coluna,
                                    "Função void '" + t.lexema + "' não devolve valor e não pode ser usada numa expressão.");
                            return Tipo.ERRO;
                        }
                        return tr;
                    } else {
                        avanca();
                        if (escopo != null && escopo.containsKey(t.lexema)) {
                            return escopo.get(t.lexema);
                        }
                        erros.adicionar(Fase.SEMANTICA, t.linha, t.coluna,
                                "Identificador '" + t.lexema + "' não foi declarado.");
                        return Tipo.ERRO;
                    }
                default:
                    erros.adicionar(Fase.SINTATICA, t.linha, t.coluna,
                            "Esperava um valor/expressão mas encontrou '" + t.lexema + "'.");
                    return Tipo.ERRO;
            }
        }

        private Tipo combinarNumerico(Tipo a, Tipo b) {
            if (a == Tipo.ERRO || b == Tipo.ERRO) return Tipo.ERRO;
            if (a == Tipo.DOUBLE || b == Tipo.DOUBLE) return Tipo.DOUBLE;
            return Tipo.INT;
        }

        private Tipo tipoDe(String lex) {
            switch (lex) {
                case "int": return Tipo.INT;
                case "double": return Tipo.DOUBLE;
                case "void": return Tipo.VOID;
                default: return Tipo.ERRO;
            }
        }
    }

    // ================================================================
    // FASE 4 — RELATÓRIO (quatro quadros)
    // ================================================================
    /** Constrói o relatório completo (quatro quadros) como texto. */
    static String gerarRelatorio(String origem, List<Token> tokens,
                                 TabelaSimbolos tabela, ColetorErros erros) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        out.println("======================================================================");
        out.println("  ANALISADOR DE CABEÇALHOS DE FUNÇÕES JAVA");
        out.println("======================================================================");

        // 1) Código fonte numerado
        out.println("\n[1] CÓDIGO FONTE");
        out.println("----------------------------------------------------------------------");
        String[] linhas = origem.split("\n", -1);
        for (int i = 0; i < linhas.length; i++) {
            out.printf("%4d | %s%n", i + 1, linhas[i]);
        }

        // 2) Tabela de lexemas
        out.println("\n[2] TABELA DE LEXEMAS (TOKENS)");
        out.println("----------------------------------------------------------------------");
        out.printf("%-18s | %-16s | %-6s | %-6s%n", "LEXEMA", "CLASSE", "LINHA", "COLUNA");
        out.println("----------------------------------------------------------------------");
        for (Token t : tokens) {
            if (t.classe == ClasseToken.EOF) continue;
            out.printf("%-18s | %-16s | %-6d | %-6d%n",
                    t.lexema, t.classe, t.linha, t.coluna);
        }

        // 3) Tabela de símbolos
        out.println("\n[3] TABELA DE SÍMBOLOS (FUNÇÕES)");
        out.println("----------------------------------------------------------------------");
        if (tabela.todas().isEmpty()) {
            out.println("  (nenhuma função declarada)");
        } else {
            out.printf("%-14s | %-8s | %-6s | %-8s | %s%n",
                    "NOME", "RETORNO", "Nº PAR", "BLOCO", "PARÂMETROS FORMAIS");
            out.println("----------------------------------------------------------------------");
            for (Funcao f : tabela.todas()) {
                StringBuilder pars = new StringBuilder();
                for (int i = 0; i < f.parametros.size(); i++) {
                    if (i > 0) pars.append(", ");
                    pars.append(f.parametros.get(i).tipo.name().toLowerCase())
                        .append(' ').append(f.parametros.get(i).nome);
                }
                out.printf("%-14s | %-8s | %-6d | %-8s | %s%n",
                        f.nome, f.tipoRetorno.name().toLowerCase(), f.parametros.size(),
                        f.blocoVazio ? "vazio" : "ok",
                        pars.length() == 0 ? "(sem parâmetros)" : pars.toString());
            }
        }

        // 4) Lista de erros e avisos
        out.println("\n[4] ERROS E AVISOS");
        out.println("----------------------------------------------------------------------");
        List<Erro> ordenados = erros.ordenados();
        if (ordenados.isEmpty()) {
            out.println("  Nenhum erro encontrado. Análise concluída com sucesso.");
        } else {
            int nErros = 0, nAvisos = 0;
            for (Erro e : ordenados) {
                String etiqueta = e.fase == Fase.AVISO ? "AVISO" : ("ERRO " + e.fase);
                out.printf("  [%-13s] linha %d, coluna %d: %s%n",
                        etiqueta, e.linha, e.coluna, e.mensagem);
                if (e.fase == Fase.AVISO) nAvisos++; else nErros++;
            }
            out.println("----------------------------------------------------------------------");
            out.printf("  Total: %d erro(s) e %d aviso(s).%n", nErros, nAvisos);
        }
        out.println("======================================================================");

        out.flush();
        return sw.toString();
    }

    /** Resultado de uma análise: relatório em texto e indicação de erros. */
    static final class Resultado {
        final String relatorio;
        final boolean temErros;
        Resultado(String relatorio, boolean temErros) {
            this.relatorio = relatorio;
            this.temErros = temErros;
        }
    }

    /** Executa as três fases sobre o código fonte e devolve o relatório. */
    static Resultado analisar(String origem) {
        ColetorErros erros = new ColetorErros();
        TabelaSimbolos tabela = new TabelaSimbolos();
        List<Token> tokens = new Lexer(origem, erros).tokenizar();   // Fase 1 — Léxica
        new Parser(tokens, erros, tabela).analisarPrograma();        // Fases 2 e 3
        String rel = gerarRelatorio(origem, tokens, tabela, erros);  // Fase 4
        return new Resultado(rel, erros.temErros());
    }

    // ================================================================
    // PROGRAMA PRINCIPAL
    // ================================================================
    private static final String EXEMPLO_EMBUTIDO =
        "// Exemplo que exercita cabeçalhos, chamadas e vários tipos de erro\n" +
        "int soma(int a, int b) {\n" +
        "    return a + b;\n" +
        "}\n" +
        "\n" +
        "double media(int a, int b) {\n" +
        "    double m = soma(a, b);\n" +
        "    return m;\n" +
        "}\n" +
        "\n" +
        "void imprime(int x) {\n" +
        "}\n" +
        "\n" +
        "// função repetida (erro semântico)\n" +
        "int soma(int a, int b) {\n" +
        "    return a;\n" +
        "}\n" +
        "\n" +
        "// void com parâmetro void (erro) e retorno de valor (erro)\n" +
        "void erra(void v) {\n" +
        "    return 5;\n" +
        "}\n" +
        "\n" +
        "// chamadas ao nível de topo\n" +
        "soma(1, 2);\n" +               // ok
        "soma(1);\n" +                  // nº de parâmetros errado
        "media(3.5, 2);\n" +           // parâmetro 1 double para formal int
        "desconhecida(1);\n" +         // função não declarada
        "imprime(soma(1, 2));\n";      // ok: int -> int

    public static void main(String[] args) {
        // Modo gráfico: java AnalisadorCabecalhosFuncoes --gui
        if (args.length > 0 && (args[0].equals("--gui") || args[0].equals("-g"))) {
            SwingUtilities.invokeLater(AnalisadorGUI::new);
            return;
        }

        String origem;
        if (args.length > 0) {
            try {
                origem = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Erro ao ler o ficheiro '" + args[0] + "': " + e.getMessage());
                return;
            }
        } else {
            origem = EXEMPLO_EMBUTIDO;
            System.out.println("(sem ficheiro indicado — a analisar o exemplo embutido; use --gui para a interface gráfica)\n");
        }

        Resultado r = analisar(origem);
        System.out.print(r.relatorio);

        // Código de saída: 1 se houve erros (não conta avisos)
        if (r.temErros) System.exit(1);
    }

    // ================================================================
    // INTERFACE GRÁFICA (Swing) — mesma lógica, com janela desktop
    // ================================================================
    /**
     * Janela Swing simples: à esquerda escreve-se/cola-se o código, à direita
     * aparece o relatório das quatro fases. Sem dependências externas.
     */
    static final class AnalisadorGUI extends JFrame {
        private final JTextArea areaCodigo;
        private final JTextArea areaRelatorio;
        private final JLabel estado;

        AnalisadorGUI() {
            super("Analisador de Cabeçalhos de Funções Java");
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignorado) { /* mantém o aspecto por omissão */ }

            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

            // ---- Área de código (entrada) ----
            areaCodigo = new JTextArea(EXEMPLO_EMBUTIDO);
            areaCodigo.setFont(mono);
            areaCodigo.setBorder(new EmptyBorder(6, 8, 6, 8));
            JScrollPane spCodigo = new JScrollPane(areaCodigo);
            spCodigo.setBorder(BorderFactory.createTitledBorder("Código fonte"));

            // ---- Área de relatório (saída) ----
            areaRelatorio = new JTextArea();
            areaRelatorio.setFont(mono);
            areaRelatorio.setEditable(false);
            areaRelatorio.setBorder(new EmptyBorder(6, 8, 6, 8));
            JScrollPane spRelatorio = new JScrollPane(areaRelatorio);
            spRelatorio.setBorder(BorderFactory.createTitledBorder("Relatório (Léxico · Sintáctico · Semântico)"));

            JSplitPane divisor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spCodigo, spRelatorio);
            divisor.setResizeWeight(0.42);

            estado = new JLabel(" ");
            estado.setBorder(new EmptyBorder(2, 10, 6, 10));

            // ---- Barra de botões ----
            JButton btAnalisar = new JButton("Analisar");
            JButton btExemplo = new JButton("Exemplo");
            JButton btAbrir = new JButton("Abrir ficheiro…");
            JButton btLimpar = new JButton("Limpar");

            btAnalisar.addActionListener(e -> analisarAgora());
            btExemplo.addActionListener(e -> { areaCodigo.setText(EXEMPLO_EMBUTIDO); analisarAgora(); });
            btAbrir.addActionListener(e -> abrirFicheiro());
            btLimpar.addActionListener(e -> { areaCodigo.setText(""); areaRelatorio.setText(""); estado.setText(" "); });

            JPanel botoes = new JPanel(new GridLayout(1, 0, 8, 0));
            botoes.setBorder(new EmptyBorder(8, 8, 4, 8));
            botoes.add(btAnalisar);
            botoes.add(btExemplo);
            botoes.add(btAbrir);
            botoes.add(btLimpar);

            JPanel topo = new JPanel(new BorderLayout());
            topo.add(botoes, BorderLayout.CENTER);

            JPanel rodape = new JPanel(new BorderLayout());
            rodape.add(estado, BorderLayout.WEST);

            add(topo, BorderLayout.NORTH);
            add(divisor, BorderLayout.CENTER);
            add(rodape, BorderLayout.SOUTH);

            setPreferredSize(new Dimension(1050, 640));
            pack();
            setLocationRelativeTo(null);
            setVisible(true);

            analisarAgora(); // análise inicial do exemplo
        }

        private void analisarAgora() {
            String origem = areaCodigo.getText();
            Resultado r = analisar(origem);
            areaRelatorio.setText(r.relatorio);
            areaRelatorio.setCaretPosition(0);
            if (r.temErros) {
                estado.setText("Foram encontrados erros — ver o quadro [4] do relatório.");
                estado.setForeground(new Color(0xB00020));
            } else {
                estado.setText("Análise concluída sem erros.");
                estado.setForeground(new Color(0x1B7F3B));
            }
        }

        private void abrirFicheiro() {
            JFileChooser selector = new JFileChooser();
            if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = selector.getSelectedFile();
                try {
                    String conteudo = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    areaCodigo.setText(conteudo);
                    analisarAgora();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this,
                            "Não foi possível ler o ficheiro:\n" + ex.getMessage(),
                            "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }
}
