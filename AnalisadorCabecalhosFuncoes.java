import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.text.*;
import javax.swing.tree.*;

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

    /**
     * Entrada da tabela de símbolos, na notação clássica (uma linha por
     * identificador declarado). Todos os campos são texto para facilitar a
     * apresentação; um campo vazio ("") significa "não aplicável".
     */
    static final class Simbolo {
        final String identificador;
        final String categoria;          // classe / constante / funcao / parametro / variável / programa
        final String tipo;               // int / double / void / ----
        final String estruturaMemoria;   // primitivo / objecto / array / ----
        final String nivel;              // numeração hierárquica de âmbito: 0, 0.1, 0.2, 0.2.1, ...
        final String numParametros;      // nº de parâmetros formais (funções)
        final String sequenciaParametros;// sequência dos tipos dos parâmetros
        final String formaPassagem;      // valor / referencia
        final String valor;              // valor literal (constantes / inicializações)
        final String dimensao;           // dimensão (arrays)
        final String referencia;         // etiqueta de referência: R1, R2, ...

        Simbolo(String identificador, String categoria, String tipo, String estruturaMemoria,
                String nivel, String numParametros, String sequenciaParametros, String formaPassagem,
                String valor, String dimensao, String referencia) {
            this.identificador = identificador;
            this.categoria = categoria;
            this.tipo = tipo;
            this.estruturaMemoria = estruturaMemoria;
            this.nivel = nivel;
            this.numParametros = numParametros;
            this.sequenciaParametros = sequenciaParametros;
            this.formaPassagem = formaPassagem;
            this.valor = valor;
            this.dimensao = dimensao;
            this.referencia = referencia;
        }
    }

    /** Tabela de símbolos: funções (para resolução de chamadas) e entradas na notação clássica. */
    static final class TabelaSimbolos {
        private final Map<String, Funcao> funcoes = new LinkedHashMap<>();
        private final List<Simbolo> simbolos = new ArrayList<>();

        boolean existe(String nome) { return funcoes.containsKey(nome); }
        Funcao obter(String nome) { return funcoes.get(nome); }
        void inserir(Funcao f) { funcoes.put(f.nome, f); }
        List<Funcao> todas() { return new ArrayList<>(funcoes.values()); }

        void adicionarSimbolo(Simbolo s) { simbolos.add(s); }
        List<Simbolo> simbolos() { return simbolos; }
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
    // ÁRVORE SINTÁCTICA ABSTRACTA (AST)
    // ================================================================
    /** Nó genérico da AST: um rótulo e os seus filhos. */
    static final class NoAST {
        final String rotulo;
        final List<NoAST> filhos = new ArrayList<>();
        NoAST(String rotulo) { this.rotulo = rotulo; }
        NoAST add(NoAST f) { if (f != null) filhos.add(f); return this; }
        NoAST add(String r) { filhos.add(new NoAST(r)); return this; }
        @Override public String toString() { return rotulo; }
    }

    /** Resultado da análise de uma expressão: tipo, nó da AST e valor literal (se houver). */
    static final class ResExpr {
        final Tipo tipo;
        final NoAST no;
        final String valor;   // preenchido apenas quando a expressão é um literal simples
        ResExpr(Tipo tipo, NoAST no) { this(tipo, no, null); }
        ResExpr(Tipo tipo, NoAST no, String valor) { this.tipo = tipo; this.no = no; this.valor = valor; }
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
        final NoAST raiz = new NoAST("programa");   // raiz da AST construída durante o parsing
        private int pos = 0;

        // Estado para a numeração da tabela de símbolos
        private int contadorFuncoes = 0;    // funções de topo: nível 0.1, 0.2, ...
        private int contadorRef = 0;        // etiquetas de referência R1, R2, ...
        private String nivelFuncaoAtual = "0";
        private int contadorVarLocal = 0;   // variáveis locais: nível 0.k.1, 0.k.2, ...

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
            // Raiz do âmbito (nível 0)
            tabela.adicionarSimbolo(new Simbolo("programa", "programa", "----", "----",
                    "0", "", "", "", "", "", ""));
            while (!fim()) {
                int posAntes = pos;
                if (ehInicioDeFuncaoOuDecl()) {
                    raiz.add(analisarFuncao());
                } else if (verifica(ClasseToken.IDENTIFICADOR)) {
                    // Chamada de função ao nível de topo, terminada por ';'
                    raiz.add(analisarChamadaComoInstrucao(new java.util.HashMap<>()));
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
        private NoAST analisarFuncao() {
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

            // Nó da AST para esta função
            NoAST noFuncao = new NoAST("função " + nomeFuncao + " : " + tipoRet.name().toLowerCase());
            NoAST noParams = new NoAST("parâmetros formais (" + formais.size() + ")");
            for (Parametro p : formais) {
                noParams.add(p.tipo.name().toLowerCase() + " " + p.nome);
            }
            noFuncao.add(noParams);

            // Tabela de símbolos (notação clássica): função + parâmetros formais
            contadorFuncoes++;
            String nivel = "0." + contadorFuncoes;
            String ref = formais.isEmpty() ? "" : "R" + (++contadorRef);
            tabela.adicionarSimbolo(new Simbolo(nomeFuncao, "funcao",
                    tipoRet.name().toLowerCase(),
                    tipoRet == Tipo.VOID ? "----" : "primitivo",
                    nivel, String.valueOf(formais.size()), sequenciaTipos(formais),
                    "", "", "", ref));
            for (Parametro p : formais) {
                tabela.adicionarSimbolo(new Simbolo(p.nome, "parametro",
                        p.tipo.name().toLowerCase(), "primitivo", nivel,
                        "", "", "valor", "", "", ref));
            }
            nivelFuncaoAtual = nivel;
            contadorVarLocal = 0;

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

            NoAST corpo = analisarBloco();
            noFuncao.add(corpo);
            int instrucoes = corpo.filhos.size();
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
            return noFuncao;
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
        /** Analisa um bloco '{ ... }' e devolve o nó "corpo" com as instruções. */
        private NoAST analisarBloco() {
            NoAST corpo = new NoAST("corpo");
            if (!verifica(ClasseToken.ABRE_CHAVE)) {
                consome(ClasseToken.ABRE_CHAVE, "'{' a abrir o corpo da função");
                return corpo;
            }
            avanca(); // {
            while (!verifica(ClasseToken.FECHA_CHAVE) && !fim()) {
                int posAntes = pos;
                NoAST instr = analisarInstrucao();
                if (instr != null) corpo.add(instr);
                if (pos == posAntes) { sincronizar(); }
            }
            consome(ClasseToken.FECHA_CHAVE, "'}' a fechar o corpo da função");
            return corpo;
        }

        // ------------------------------ instrucao ------------------------------
        private NoAST analisarInstrucao() {
            // Declaração de variável local: tipoParam IDENT [= expr] ;
            if (verificaLexema("int") || verificaLexema("double")) {
                return analisarDeclaracaoVariavel();
            }
            if (verificaLexema("void")) {
                Token t = atual();
                erros.adicionar(Fase.SEMANTICA, t.linha, t.coluna,
                        "'void' não pode ser usado como tipo de variável local.");
                avanca();
                sincronizar();
                return new NoAST("declaração inválida (void)");
            }
            // return [expr] ;
            if (verificaLexema("return")) {
                return analisarRetorno();
            }
            // IDENT ... -> chamada ou atribuição
            if (verifica(ClasseToken.IDENTIFICADOR)) {
                if (espreita(1).classe == ClasseToken.ABRE_PAR) {
                    return analisarChamadaComoInstrucao(escopo);
                } else {
                    return analisarAtribuicao();
                }
            }
            // Ponto e vírgula solto = instrução vazia (tolerada)
            if (verifica(ClasseToken.PONTO_VIRGULA)) { avanca(); return null; }

            Token t = atual();
            erros.adicionar(Fase.SINTATICA, t.linha, t.coluna,
                    "Instrução inválida a começar em '" + t.lexema + "'.");
            return null;
        }

        private NoAST analisarDeclaracaoVariavel() {
            Token tTipo = avanca();
            Tipo tipo = tipoDe(tTipo.lexema);
            Token nome = consome(ClasseToken.IDENTIFICADOR, "o nome da variável");
            String nomeVar = nome != null ? nome.lexema : "<sem-nome>";
            if (nome != null) {
                if (escopo.containsKey(nome.lexema)) {
                    erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                            "Variável '" + nome.lexema + "' já declarada neste escopo.");
                } else {
                    escopo.put(nome.lexema, tipo);
                }
            }
            NoAST no = new NoAST("declaração " + tipo.name().toLowerCase() + " " + nomeVar);
            String valorLiteral = "";
            if (verifica(ClasseToken.OP_ATRIB)) {
                avanca();
                ResExpr r = analisarExpressao();
                if (r.valor != null) valorLiteral = r.valor;
                if (nome != null && !Tipo.compativel(tipo, r.tipo)) {
                    erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                            "Não é possível atribuir valor " + r.tipo.name().toLowerCase()
                            + " à variável '" + nome.lexema + "' do tipo " + tipo.name().toLowerCase() + ".");
                }
                NoAST atrib = new NoAST("inicialização '='");
                atrib.add(r.no);
                no.add(atrib);
            }
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim da declaração");

            // Tabela de símbolos: variável local (nível 0.k.j)
            contadorVarLocal++;
            tabela.adicionarSimbolo(new Simbolo(nomeVar, "variável",
                    tipo.name().toLowerCase(), "primitivo",
                    nivelFuncaoAtual + "." + contadorVarLocal,
                    "", "", "", valorLiteral, "", ""));
            return no;
        }

        private NoAST analisarAtribuicao() {
            Token nome = avanca(); // IDENT
            Tipo tipoVar = escopo.get(nome.lexema);
            if (tipoVar == null) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Variável '" + nome.lexema + "' não foi declarada.");
                tipoVar = Tipo.ERRO;
            }
            consome(ClasseToken.OP_ATRIB, "'=' na atribuição");
            ResExpr r = analisarExpressao();
            if (!Tipo.compativel(tipoVar, r.tipo)) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Não é possível atribuir valor " + r.tipo.name().toLowerCase()
                        + " à variável '" + nome.lexema + "' do tipo " + tipoVar.name().toLowerCase() + ".");
            }
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim da atribuição");
            NoAST no = new NoAST("atribuição " + nome.lexema + " =");
            no.add(r.no);
            return no;
        }

        private NoAST analisarRetorno() {
            Token tReturn = avanca(); // return
            NoAST no = new NoAST("return");
            if (verifica(ClasseToken.PONTO_VIRGULA)) {
                avanca();
                // 'return;' sem valor
                if (funcaoAtual != null && funcaoAtual.tipoRetorno != Tipo.VOID) {
                    erros.adicionar(Fase.SEMANTICA, tReturn.linha, tReturn.coluna,
                            "Função '" + funcaoAtual.nome + "' do tipo "
                            + funcaoAtual.tipoRetorno.name().toLowerCase()
                            + " deve retornar um valor.");
                }
                return no;
            }
            ResExpr r = analisarExpressao();
            no.add(r.no);
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim do 'return'");
            if (funcaoAtual != null) {
                if (funcaoAtual.tipoRetorno == Tipo.VOID) {
                    erros.adicionar(Fase.SEMANTICA, tReturn.linha, tReturn.coluna,
                            "Função '" + funcaoAtual.nome + "' é void (sem retorno) e não pode retornar valor.");
                } else if (!Tipo.compativel(funcaoAtual.tipoRetorno, r.tipo)) {
                    erros.adicionar(Fase.SEMANTICA, tReturn.linha, tReturn.coluna,
                            "Retorno de tipo " + r.tipo.name().toLowerCase() + " incompatível com o tipo "
                            + funcaoAtual.tipoRetorno.name().toLowerCase() + " da função '" + funcaoAtual.nome + "'.");
                } else {
                    encontrouRetornoValido = true;
                }
            }
            return no;
        }

        // ------------------------------ chamada ------------------------------
        private NoAST analisarChamadaComoInstrucao(Map<String, Tipo> escopoLocal) {
            ResExpr r = analisarChamada(escopoLocal);
            consome(ClasseToken.PONTO_VIRGULA, "';' no fim da chamada");
            return r.no;
        }

        /** Analisa uma chamada IDENT( actuais ) e devolve o tipo de retorno e o nó da AST. */
        private ResExpr analisarChamada(Map<String, Tipo> escopoLocal) {
            Token nome = avanca(); // IDENT
            consome(ClasseToken.ABRE_PAR, "'(' na chamada de função");

            NoAST no = new NoAST("chamada " + nome.lexema + "(...)");
            List<Tipo> actuais = new ArrayList<>();
            if (!verifica(ClasseToken.FECHA_PAR)) {
                do {
                    ResExpr arg = analisarExpressao();
                    actuais.add(arg.tipo);
                    NoAST noArg = new NoAST("arg " + actuais.size() + " : " + arg.tipo.name().toLowerCase());
                    noArg.add(arg.no);
                    no.add(noArg);
                } while (verifica(ClasseToken.VIRGULA) && avanca() != null);
            }
            consome(ClasseToken.FECHA_PAR, "')' a fechar a chamada de função");

            // Semântica: função existe?
            Funcao f = tabela.obter(nome.lexema);
            if (f == null) {
                erros.adicionar(Fase.SEMANTICA, nome.linha, nome.coluna,
                        "Chamada à função '" + nome.lexema + "' que não foi declarada.");
                return new ResExpr(Tipo.ERRO, no);
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
            return new ResExpr(f.tipoRetorno, no);
        }

        // ------------------------------ expressao ------------------------------
        private ResExpr analisarExpressao() {
            ResExpr r = analisarTermo();
            while (verifica(ClasseToken.OP_ARIT)) {
                Token op = avanca();
                ResExpr r2 = analisarTermo();
                Tipo t = combinarNumerico(r.tipo, r2.tipo);
                NoAST no = new NoAST("operador '" + op.lexema + "' : " + t.name().toLowerCase());
                no.add(r.no);
                no.add(r2.no);
                r = new ResExpr(t, no);
            }
            return r;
        }

        private ResExpr analisarTermo() {
            Token t = atual();
            switch (t.classe) {
                case NUMERO_INT:  avanca(); return new ResExpr(Tipo.INT, new NoAST("literal int " + t.lexema), t.lexema);
                case NUMERO_REAL: avanca(); return new ResExpr(Tipo.DOUBLE, new NoAST("literal double " + t.lexema), t.lexema);
                case ABRE_PAR:
                    avanca();
                    ResExpr interno = analisarExpressao();
                    consome(ClasseToken.FECHA_PAR, "')' a fechar a subexpressão");
                    return interno;
                case IDENTIFICADOR:
                    if (espreita(1).classe == ClasseToken.ABRE_PAR) {
                        ResExpr rc = analisarChamada(escopo != null ? escopo : new java.util.HashMap<>());
                        if (rc.tipo == Tipo.VOID) {
                            erros.adicionar(Fase.SEMANTICA, t.linha, t.coluna,
                                    "Função void '" + t.lexema + "' não devolve valor e não pode ser usada numa expressão.");
                            return new ResExpr(Tipo.ERRO, rc.no);
                        }
                        return rc;
                    } else {
                        avanca();
                        if (escopo != null && escopo.containsKey(t.lexema)) {
                            Tipo tv = escopo.get(t.lexema);
                            return new ResExpr(tv, new NoAST("var " + t.lexema + " : " + tv.name().toLowerCase()));
                        }
                        erros.adicionar(Fase.SEMANTICA, t.linha, t.coluna,
                                "Identificador '" + t.lexema + "' não foi declarado.");
                        return new ResExpr(Tipo.ERRO, new NoAST("var " + t.lexema + " : erro"));
                    }
                default:
                    erros.adicionar(Fase.SINTATICA, t.linha, t.coluna,
                            "Esperava um valor/expressão mas encontrou '" + t.lexema + "'.");
                    return new ResExpr(Tipo.ERRO, new NoAST("<expressão inválida>"));
            }
        }

        private Tipo combinarNumerico(Tipo a, Tipo b) {
            if (a == Tipo.ERRO || b == Tipo.ERRO) return Tipo.ERRO;
            if (a == Tipo.DOUBLE || b == Tipo.DOUBLE) return Tipo.DOUBLE;
            return Tipo.INT;
        }

        /** Sequência dos tipos dos parâmetros formais, ex.: "int, double". */
        private String sequenciaTipos(List<Parametro> ps) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ps.get(i).tipo.name().toLowerCase());
            }
            return sb.toString();
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

        // 3) Tabela de símbolos (notação clássica)
        out.println("\n[3] TABELA DE SÍMBOLOS");
        String sepSimb = "---------------------------------------------------------------------------------------------------------------------------------";
        out.println(sepSimb);
        String fmt = "%-12s | %-10s | %-7s | %-9s | %-8s | %-6s | %-16s | %-9s | %-6s | %-4s | %-4s%n";
        out.printf(fmt, "Identific.", "Categoria", "Tipo", "Est.Mem.", "Nível",
                "NºPar", "Seq.Parâmetros", "F.Pass.", "Valor", "Dim.", "Ref.");
        out.println(sepSimb);
        for (Simbolo s : tabela.simbolos()) {
            out.printf(fmt, s.identificador, s.categoria, s.tipo, s.estruturaMemoria, s.nivel,
                    s.numParametros, s.sequenciaParametros, s.formaPassagem, s.valor,
                    s.dimensao, s.referencia);
        }
        out.println(sepSimb);

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

    /** Resultado de uma análise: estruturas das fases, relatório em texto e contagens. */
    static final class Resultado {
        final String origem;
        final List<Token> tokens;
        final TabelaSimbolos tabela;
        final ColetorErros erros;
        final NoAST ast;
        final String relatorio;
        final int nErros;
        final int nAvisos;

        Resultado(String origem, List<Token> tokens, TabelaSimbolos tabela,
                  ColetorErros erros, NoAST ast, String relatorio) {
            this.origem = origem;
            this.tokens = tokens;
            this.tabela = tabela;
            this.erros = erros;
            this.ast = ast;
            this.relatorio = relatorio;
            int e = 0, a = 0;
            for (Erro er : erros.ordenados()) { if (er.fase == Fase.AVISO) a++; else e++; }
            this.nErros = e;
            this.nAvisos = a;
        }

        boolean temErros() { return nErros > 0; }
    }

    /** Executa as três fases sobre o código fonte e devolve o relatório. */
    static Resultado analisar(String origem) {
        ColetorErros erros = new ColetorErros();
        TabelaSimbolos tabela = new TabelaSimbolos();
        List<Token> tokens = new Lexer(origem, erros).tokenizar();   // Fase 1 — Léxica
        Parser parser = new Parser(tokens, erros, tabela);
        parser.analisarPrograma();                                   // Fases 2 e 3
        String rel = gerarRelatorio(origem, tokens, tabela, erros);  // Fase 4
        return new Resultado(origem, tokens, tabela, erros, parser.raiz, rel);
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
        if (r.temErros()) System.exit(1);
    }

    // ================================================================
    // INTERFACE GRÁFICA (Swing) — tema escuro, sem dependências externas
    // ================================================================

    // Paleta e tipos de letra partilhados pelos componentes da GUI.
    private static final Color BG        = new Color(0x1E1F2B); // fundo geral
    private static final Color BG_BARRA  = new Color(0x171821); // cabeçalho
    private static final Color BG_CARTAO = new Color(0x262A3B); // cartões/relatório
    private static final Color BG_EDITOR = new Color(0x22242F); // editor de código
    private static final Color BORDA     = new Color(0x343850);
    private static final Color ACENTO    = new Color(0x7C6CFF);
    private static final Color ACENTO_H  = new Color(0x9385FF);
    private static final Color TXT       = new Color(0xE6E7EE);
    private static final Color TXT_MUTE  = new Color(0x9AA0B4);
    private static final Color COR_ERRO  = new Color(0xFF6B6B);
    private static final Color COR_AVISO = new Color(0xFFB454);
    private static final Color COR_OK    = new Color(0x4ADE80);
    // Realce de sintaxe
    private static final Color SX_KW  = new Color(0xC792EA); // palavras-chave
    private static final Color SX_NUM = new Color(0xF78C6C); // números
    private static final Color SX_CMT = new Color(0x6B7089); // comentários

    private static final Font FONTE_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final Font FONTE_UI    = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font FONTE_UI_B  = new Font(Font.SANS_SERIF, Font.BOLD, 13);

    /** Botão "flat" com cantos arredondados e efeito de hover. */
    static final class BotaoChato extends JButton {
        private final Color base, hover;
        private boolean dentro;
        BotaoChato(String txt, Color base, Color hover, Color texto) {
            super(txt);
            this.base = base;
            this.hover = hover;
            setForeground(texto);
            setFont(FONTE_UI_B);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(9, 18, 9, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { dentro = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { dentro = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(dentro ? hover : base);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Etiqueta arredondada colorida (chip) para o rodapé de estado. */
    static final class Chip extends JLabel {
        private final Color cor;
        Chip(String txt, Color cor) {
            super(txt);
            this.cor = cor;
            setForeground(cor);
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            setBorder(new EmptyBorder(5, 12, 5, 12));
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(cor.getRed(), cor.getGreen(), cor.getBlue(), 38));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.setColor(new Color(cor.getRed(), cor.getGreen(), cor.getBlue(), 120));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Régua de números de linha, sincronizada com o editor de código. */
    static final class GutterLinhas extends JComponent {
        private final JTextComponent txt;
        GutterLinhas(JTextComponent txt) {
            this.txt = txt;
            setFont(txt.getFont());
            txt.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { revalidate(); repaint(); }
                @Override public void removeUpdate(DocumentEvent e) { revalidate(); repaint(); }
                @Override public void changedUpdate(DocumentEvent e) { repaint(); }
            });
        }
        private int digitos() {
            int linhas = txt.getDocument().getDefaultRootElement().getElementCount();
            return Math.max(2, String.valueOf(linhas).length());
        }
        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.charWidth('0') * digitos() + 20, Math.max(txt.getHeight(), 1));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(BG_EDITOR);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(BORDA);
            g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            g2.setFont(getFont());
            g2.setColor(TXT_MUTE);
            Element raiz = txt.getDocument().getDefaultRootElement();
            Rectangle clip = g.getClipBounds();
            FontMetrics fm = g.getFontMetrics(getFont());
            int total = raiz.getElementCount();
            for (int i = 0; i < total; i++) {
                int off = raiz.getElement(i).getStartOffset();
                try {
                    Rectangle2D r = txt.modelToView2D(off);
                    if (r == null) continue;
                    int y = (int) r.getY();
                    int alt = (int) r.getHeight();
                    if (y + alt < clip.y || y > clip.y + clip.height) continue;
                    String n = String.valueOf(i + 1);
                    int baseline = y + fm.getAscent() + (alt - fm.getHeight()) / 2;
                    g2.drawString(n, getWidth() - 10 - fm.stringWidth(n), baseline);
                } catch (BadLocationException ex) { /* ignora */ }
            }
        }
    }

    /**
     * Janela principal: editor de código com realce e números de linha à
     * esquerda; relatório colorido das quatro fases à direita; chips de estado.
     */
    static final class AnalisadorGUI extends JFrame {
        private final JTextPane areaCodigo;
        private final JPanel painelChips;
        private final Timer debounce;
        private JTextPane areaRelatorio;
        private DefaultTableModel modeloLexemas, modeloSimbolos, modeloErros;
        private JTree arvore;

        // Estilos do realce de sintaxe
        private final SimpleAttributeSet estBase = new SimpleAttributeSet();
        private final SimpleAttributeSet estKw   = new SimpleAttributeSet();
        private final SimpleAttributeSet estNum  = new SimpleAttributeSet();
        private final SimpleAttributeSet estCmt  = new SimpleAttributeSet();

        AnalisadorGUI() {
            super("Analisador de Cabeçalhos de Funções Java");
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignorado) { /* mantém o aspecto por omissão */ }

            StyleConstants.setForeground(estBase, TXT);
            StyleConstants.setForeground(estKw, SX_KW);  StyleConstants.setBold(estKw, true);
            StyleConstants.setForeground(estNum, SX_NUM);
            StyleConstants.setForeground(estCmt, SX_CMT); StyleConstants.setItalic(estCmt, true);

            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            getContentPane().setBackground(BG);
            ((JComponent) getContentPane()).setBorder(new EmptyBorder(0, 0, 0, 0));

            // ---- Editor de código (não quebra linhas: scroll horizontal) ----
            areaCodigo = new JTextPane() {
                @Override public boolean getScrollableTracksViewportWidth() {
                    return getUI().getPreferredSize(this).width <= getParent().getSize().width;
                }
            };
            areaCodigo.setFont(FONTE_MONO);
            areaCodigo.setBackground(BG_EDITOR);
            areaCodigo.setForeground(TXT);
            areaCodigo.setCaretColor(TXT);
            areaCodigo.setSelectionColor(new Color(0x3A3F5F));
            areaCodigo.setSelectedTextColor(TXT);
            areaCodigo.setBorder(new EmptyBorder(10, 12, 10, 12));
            areaCodigo.setText(EXEMPLO_EMBUTIDO);
            areaCodigo.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { aoEditar(); }
                @Override public void removeUpdate(DocumentEvent e) { aoEditar(); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });

            JScrollPane spCodigo = new JScrollPane(areaCodigo);
            spCodigo.setBorder(null);
            spCodigo.getViewport().setBackground(BG_EDITOR);
            spCodigo.setRowHeaderView(new GutterLinhas(areaCodigo));

            // ---- Painel de resultados com abas ----
            JTabbedPane abas = construirAbas();

            JSplitPane divisor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    construirCartao("CÓDIGO FONTE", spCodigo),
                    construirCartao("ANÁLISE", abas));
            divisor.setResizeWeight(0.42);
            divisor.setBorder(new EmptyBorder(10, 12, 6, 12));
            divisor.setBackground(BG);
            divisor.setDividerSize(10);

            // ---- Rodapé com chips de estado ----
            painelChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            painelChips.setBackground(BG);
            painelChips.setBorder(new EmptyBorder(0, 8, 6, 8));

            // ---- Montagem ----
            JPanel norte = new JPanel(new BorderLayout());
            norte.setBackground(BG);
            norte.add(construirCabecalho(), BorderLayout.NORTH);
            norte.add(construirBarra(), BorderLayout.SOUTH);

            add(norte, BorderLayout.NORTH);
            add(divisor, BorderLayout.CENTER);
            add(painelChips, BorderLayout.SOUTH);

            debounce = new Timer(250, e -> analisarAgora());
            debounce.setRepeats(false);

            setPreferredSize(new Dimension(1080, 680));
            pack();
            setLocationRelativeTo(null);
            setVisible(true);

            realcarSintaxe();
            analisarAgora();
        }

        // ---- Cabeçalho (título + subtítulo + barra de acento) ----
        private JComponent construirCabecalho() {
            JPanel textos = new JPanel();
            textos.setOpaque(false);
            textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
            JLabel titulo = new JLabel("Analisador de Cabeçalhos de Funções Java");
            titulo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
            titulo.setForeground(TXT);
            titulo.setAlignmentX(LEFT_ALIGNMENT);
            JLabel sub = new JLabel("Funções int / double / void  ·  parâmetros formais vs. actuais  ·  blocos vazios");
            sub.setFont(FONTE_UI);
            sub.setForeground(TXT_MUTE);
            sub.setAlignmentX(LEFT_ALIGNMENT);
            textos.add(titulo);
            textos.add(Box.createVerticalStrut(3));
            textos.add(sub);

            JPanel barraTitulo = new JPanel(new BorderLayout());
            barraTitulo.setBackground(BG_BARRA);
            barraTitulo.setBorder(new EmptyBorder(16, 22, 16, 22));
            barraTitulo.add(textos, BorderLayout.WEST);

            JPanel acento = new JPanel();
            acento.setBackground(ACENTO);
            acento.setPreferredSize(new Dimension(0, 3));

            JPanel wrap = new JPanel(new BorderLayout());
            wrap.add(barraTitulo, BorderLayout.CENTER);
            wrap.add(acento, BorderLayout.SOUTH);
            return wrap;
        }

        // ---- Barra de botões ----
        private JComponent construirBarra() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
            p.setBackground(BG);
            BotaoChato bAnalisar = new BotaoChato("Analisar", ACENTO, ACENTO_H, Color.WHITE);
            BotaoChato bExemplo  = new BotaoChato("Exemplo", BG_CARTAO, BORDA, TXT);
            BotaoChato bAbrir    = new BotaoChato("Abrir ficheiro…", BG_CARTAO, BORDA, TXT);
            BotaoChato bLimpar   = new BotaoChato("Limpar", BG_CARTAO, BORDA, TXT);
            bAnalisar.addActionListener(e -> analisarAgora());
            bExemplo.addActionListener(e -> definirCodigo(EXEMPLO_EMBUTIDO));
            bAbrir.addActionListener(e -> abrirFicheiro());
            bLimpar.addActionListener(e -> definirCodigo(""));
            p.add(bAnalisar);
            p.add(bExemplo);
            p.add(bAbrir);
            p.add(bLimpar);
            return p;
        }

        // ---- Cartão com título e conteúdo ----
        private JComponent construirCartao(String titulo, JComponent centro) {
            JLabel lbl = new JLabel(titulo);
            lbl.setFont(FONTE_UI_B);
            lbl.setForeground(TXT_MUTE);
            JPanel cabec = new JPanel(new BorderLayout());
            cabec.setBackground(BG_CARTAO);
            cabec.setBorder(new EmptyBorder(9, 14, 9, 14));
            cabec.add(lbl, BorderLayout.WEST);

            JPanel cartao = new JPanel(new BorderLayout());
            cartao.setBackground(BG_CARTAO);
            cartao.setBorder(new LineBorder(BORDA, 1, true));
            cartao.add(cabec, BorderLayout.NORTH);
            cartao.add(centro, BorderLayout.CENTER);
            return cartao;
        }

        private void aoEditar() {
            SwingUtilities.invokeLater(this::realcarSintaxe); // evita mutação durante notificação
            debounce.restart();
        }

        private void definirCodigo(String s) {
            areaCodigo.setText(s);
            realcarSintaxe();
            analisarAgora();
        }

        // ---- Realce de sintaxe do editor ----
        private void realcarSintaxe() {
            StyledDocument doc = areaCodigo.getStyledDocument();
            String texto;
            try { texto = doc.getText(0, doc.getLength()); }
            catch (BadLocationException e) { return; }
            doc.setCharacterAttributes(0, doc.getLength(), estBase, true);
            aplicar(doc, texto, "\\b(int|double|void|return)\\b", estKw, 0);
            aplicar(doc, texto, "\\b\\d+(\\.\\d+)?\\b", estNum, 0);
            aplicar(doc, texto, "//[^\\n]*", estCmt, 0);                 // comentário de linha
            aplicar(doc, texto, "/\\*.*?\\*/", estCmt, Pattern.DOTALL);  // comentário de bloco
        }

        private void aplicar(StyledDocument doc, String texto, String regex, AttributeSet est, int flags) {
            Matcher m = Pattern.compile(regex, flags).matcher(texto);
            while (m.find()) {
                doc.setCharacterAttributes(m.start(), m.end() - m.start(), est, false);
            }
        }

        // ---- Painel de abas (Resumo, Lexemas, Símbolos, AST, Erros) ----
        private JTabbedPane construirAbas() {
            UIManager.put("TabbedPane.selected", BG_CARTAO);
            UIManager.put("TabbedPane.background", BG);
            UIManager.put("TabbedPane.foreground", TXT);
            JTabbedPane abas = new JTabbedPane();
            abas.setBackground(BG);
            abas.setForeground(TXT);
            abas.setFont(FONTE_UI_B);

            // Resumo: relatório colorido
            areaRelatorio = new JTextPane();
            areaRelatorio.setEditable(false);
            areaRelatorio.setBackground(BG_CARTAO);
            areaRelatorio.setBorder(new EmptyBorder(10, 12, 10, 12));
            abas.addTab("Resumo", envolver(areaRelatorio));

            // Tabela de lexemas
            modeloLexemas = modeloFixo("#", "Lexema", "Classe", "Linha", "Coluna");
            abas.addTab("Lexemas", envolver(criarTabela(modeloLexemas)));

            // Tabela de símbolos (notação clássica)
            modeloSimbolos = modeloFixo("Identificador", "Categoria", "Tipo", "Estrutura Memória",
                    "Nível", "Nº Parâm.", "Sequência Parâm.", "Forma Passagem", "Valor",
                    "Dimensão", "Referência");
            abas.addTab("Símbolos", envolver(criarTabela(modeloSimbolos)));

            // AST
            arvore = new JTree(new DefaultMutableTreeNode("programa"));
            estilizarArvore(arvore);
            abas.addTab("AST", envolver(arvore));

            // Tabela de erros (com tipo, linha, coluna e mensagem)
            modeloErros = modeloFixo("Tipo", "Linha", "Coluna", "Mensagem");
            JTable tabErros = criarTabela(modeloErros);
            tabErros.getColumnModel().getColumn(0).setPreferredWidth(95);
            tabErros.getColumnModel().getColumn(1).setPreferredWidth(45);
            tabErros.getColumnModel().getColumn(2).setPreferredWidth(50);
            tabErros.getColumnModel().getColumn(3).setPreferredWidth(380);
            tabErros.setDefaultRenderer(Object.class, new RendererErros(modeloErros));
            abas.addTab("Erros", envolver(tabErros));

            return abas;
        }

        private JScrollPane envolver(JComponent c) {
            JScrollPane sp = new JScrollPane(c);
            sp.setBorder(null);
            sp.getViewport().setBackground(BG_CARTAO);
            return sp;
        }

        private DefaultTableModel modeloFixo(String... colunas) {
            return new DefaultTableModel(colunas, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
        }

        private JTable criarTabela(DefaultTableModel modelo) {
            JTable t = new JTable(modelo);
            t.setBackground(BG_CARTAO);
            t.setForeground(TXT);
            t.setGridColor(BORDA);
            t.setRowHeight(24);
            t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            t.setSelectionBackground(new Color(0x3A3F5F));
            t.setSelectionForeground(TXT);
            t.setFillsViewportHeight(true);
            JTableHeader h = t.getTableHeader();
            h.setBackground(BG_BARRA);
            h.setForeground(TXT_MUTE);
            h.setFont(FONTE_UI_B);
            return t;
        }

        private void estilizarArvore(JTree arv) {
            arv.setBackground(BG_CARTAO);
            arv.setForeground(TXT);
            arv.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            arv.setRowHeight(22);
            DefaultTreeCellRenderer rend = new DefaultTreeCellRenderer();
            rend.setBackgroundNonSelectionColor(BG_CARTAO);
            rend.setBackgroundSelectionColor(new Color(0x3A3F5F));
            rend.setTextNonSelectionColor(TXT);
            rend.setTextSelectionColor(TXT);
            rend.setBorderSelectionColor(BORDA);
            rend.setLeafIcon(null);
            rend.setOpenIcon(null);
            rend.setClosedIcon(null);
            arv.setCellRenderer(rend);
        }

        /** Renderer da tabela de erros: pinta a linha de vermelho (erro) ou laranja (aviso). */
        private final class RendererErros extends DefaultTableCellRenderer {
            private final DefaultTableModel modelo;
            RendererErros(DefaultTableModel modelo) {
                this.modelo = modelo;
                setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            }
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foco, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, foco, row, col);
                String tipo = String.valueOf(modelo.getValueAt(row, 0));
                Color cor = tipo.startsWith("Aviso") ? COR_AVISO : COR_ERRO;
                setForeground(sel ? TXT : cor);
                setBackground(sel ? new Color(0x3A3F5F) : BG_CARTAO);
                return this;
            }
        }

        // ---- Análise + render ----
        private void analisarAgora() {
            Resultado r = analisar(areaCodigo.getText());
            renderRelatorio(r);
            atualizarTabelas(r);
            atualizarChips(r);
        }

        private void atualizarTabelas(Resultado r) {
            // Lexemas
            modeloLexemas.setRowCount(0);
            int i = 1;
            for (Token tk : r.tokens) {
                if (tk.classe == ClasseToken.EOF) continue;
                modeloLexemas.addRow(new Object[]{ i++, tk.lexema, tk.classe, tk.linha, tk.coluna });
            }
            // Símbolos (notação clássica)
            modeloSimbolos.setRowCount(0);
            for (Simbolo s : r.tabela.simbolos()) {
                modeloSimbolos.addRow(new Object[]{ s.identificador, s.categoria, s.tipo,
                        s.estruturaMemoria, s.nivel, s.numParametros, s.sequenciaParametros,
                        s.formaPassagem, s.valor, s.dimensao, s.referencia });
            }
            // Erros
            modeloErros.setRowCount(0);
            for (Erro e : r.erros.ordenados()) {
                modeloErros.addRow(new Object[]{ rotuloFase(e.fase), e.linha, e.coluna, e.mensagem });
            }
            // AST
            arvore.setModel(new DefaultTreeModel(converter(r.ast)));
            for (int k = 0; k < arvore.getRowCount(); k++) arvore.expandRow(k);
        }

        private String rotuloFase(Fase f) {
            switch (f) {
                case LEXICA: return "Léxico";
                case SINTATICA: return "Sintáctico";
                case SEMANTICA: return "Semântico";
                default: return "Aviso";
            }
        }

        private DefaultMutableTreeNode converter(NoAST no) {
            DefaultMutableTreeNode d = new DefaultMutableTreeNode(no.rotulo);
            for (NoAST filho : no.filhos) d.add(converter(filho));
            return d;
        }

        private void renderRelatorio(Resultado r) {
            StyledDocument d = new DefaultStyledDocument();
            for (String linha : r.relatorio.split("\n", -1)) {
                try { d.insertString(d.getLength(), linha + "\n", estiloLinha(linha)); }
                catch (BadLocationException ignorado) { }
            }
            areaRelatorio.setDocument(d);
            areaRelatorio.setCaretPosition(0);
        }

        private AttributeSet estiloLinha(String linha) {
            if (linha.contains("====")) return attr(SX_CMT, false);
            if (linha.startsWith("[")) return attr(ACENTO, true);              // títulos [1]..[4]
            if (linha.contains("ANALISADOR DE CAB")) return attr(ACENTO, true);
            if (linha.contains("[ERRO")) return attr(COR_ERRO, false);
            if (linha.contains("[AVISO")) return attr(COR_AVISO, false);
            if (linha.contains("Nenhum erro")) return attr(COR_OK, true);
            if (linha.trim().startsWith("Total:")) return attr(TXT, true);
            if (linha.matches("\\s*\\d+ \\|.*")) return attr(TXT_MUTE, false); // listagem do código
            return attr(TXT, false);
        }

        private AttributeSet attr(Color c, boolean bold) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, c);
            StyleConstants.setBold(a, bold);
            StyleConstants.setFontFamily(a, Font.MONOSPACED);
            StyleConstants.setFontSize(a, 13);
            return a;
        }

        private void atualizarChips(Resultado r) {
            painelChips.removeAll();
            painelChips.add(new Chip(r.nErros == 0 ? "Sem erros" : r.nErros + " erro(s)",
                    r.nErros > 0 ? COR_ERRO : COR_OK));
            painelChips.add(new Chip(r.nAvisos + " aviso(s)", r.nAvisos > 0 ? COR_AVISO : TXT_MUTE));
            painelChips.add(new Chip(r.tabela.todas().size() + " função(ões)", ACENTO));
            painelChips.add(new Chip(Math.max(0, r.tokens.size() - 1) + " tokens", TXT_MUTE));
            painelChips.revalidate();
            painelChips.repaint();
        }

        private void abrirFicheiro() {
            JFileChooser selector = new JFileChooser();
            if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = selector.getSelectedFile();
                try {
                    definirCodigo(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this,
                            "Não foi possível ler o ficheiro:\n" + ex.getMessage(),
                            "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }
}
