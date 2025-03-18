package ai.core.document.textsplitters;

import java.util.Arrays;
import java.util.List;

/**
 * @author stephen
 */
@SuppressWarnings("FileLength")
public class LanguageSeparators {

    public static List<String> getSeparatorsForLanguage(Language language) {
        return switch (language) {
            case C, CPP -> handleCAndCpp();
            case GO -> handleGo();
            case JAVA -> handleJava();
            case KOTLIN -> handleKotlin();
            case JS -> handleJs();
            case TS -> handleTs();
            case PHP -> handlePhp();
            case PROTO -> handleProto();
            case PYTHON -> handlePython();
            case RST -> handleRst();
            case RUBY -> handleRuby();
            case ELIXIR -> handleElixir();
            case RUST -> handleRust();
            case SCALA -> handleScala();
            case SWIFT -> handleSwift();
            case MARKDOWN -> handleMarkdown();
            case LATEX -> handleLatex();
            case HTML -> handleHtml();
            case CSHARP -> handleCsharp();
            case SOL -> handleSol();
            case COBOL -> handleCobol();
            case LUA -> handleLua();
            case HASKELL -> handleHaskell();
            case POWERSHELL -> handlePowershell();
        };
    }

    private static List<String> handleCAndCpp() {
        return Arrays.asList(
                "\nclass ",
                "\nvoid ",
                "\nint ",
                "\nfloat ",
                "\ndouble ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nswitch ",
                "\ncase ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleGo() {
        return Arrays.asList(
                "\nfunc ",
                "\nvar ",
                "\nconst ",
                "\ntype ",
                "\nif ",
                "\nfor ",
                "\nswitch ",
                "\ncase ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleJava() {
        return Arrays.asList(
                "\nclass ",
                "\npublic ",
                "\nprotected ",
                "\nprivate ",
                "\nstatic ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nswitch ",
                "\ncase ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleKotlin() {
        return Arrays.asList(
                "\nclass ",
                "\npublic ",
                "\nprotected ",
                "\nprivate ",
                "\ninternal ",
                "\ncompanion ",
                "\nfun ",
                "\nval ",
                "\nvar ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nwhen ",
                "\ncase ",
                "\nelse ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleJs() {
        return Arrays.asList(
                "\nfunction ",
                "\nconst ",
                "\nlet ",
                "\nvar ",
                "\nclass ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nswitch ",
                "\ncase ",
                "\ndefault ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handlePython() {
        return Arrays.asList(
                "\nclass ",
                "\ndef ",
                "\n\tdef ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleHtml() {
        return Arrays.asList(
                "<body",
                "<div",
                "<p",
                "<br",
                "<li",
                "<h1",
                "<h2",
                "<h3",
                "<h4",
                "<h5",
                "<h6",
                "<span",
                "<table",
                "<tr",
                "<td",
                "<th",
                "<ul",
                "<ol",
                "<header",
                "<footer",
                "<nav",
                "<head",
                "<style",
                "<script",
                "<meta",
                "<title",
                ""
        );
    }
    private static List<String> handleTs() {
        return Arrays.asList(
                "\nenum ",
                "\ninterface ",
                "\nnamespace ",
                "\ntype ",
                "\nclass ",
                "\nfunction ",
                "\nconst ",
                "\nlet ",
                "\nvar ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nswitch ",
                "\ncase ",
                "\ndefault ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handlePhp() {
        return Arrays.asList(
                "\nfunction ",
                "\nclass ",
                "\nif ",
                "\nforeach ",
                "\nwhile ",
                "\ndo ",
                "\nswitch ",
                "\ncase ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleProto() {
        return Arrays.asList(
                "\nmessage ",
                "\nservice ",
                "\nenum ",
                "\noption ",
                "\nimport ",
                "\nsyntax ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleRst() {
        return Arrays.asList(
                "\n=+\n",
                "\n-+\n",
                "\n\\*+\n",
                "\n\n.. *\n\n",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleRuby() {
        return Arrays.asList(
                "\ndef ",
                "\nclass ",
                "\nif ",
                "\nunless ",
                "\nwhile ",
                "\nfor ",
                "\ndo ",
                "\nbegin ",
                "\nrescue ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleElixir() {
        return Arrays.asList(
                "\ndef ",
                "\ndefp ",
                "\ndefmodule ",
                "\ndefprotocol ",
                "\ndefmacro ",
                "\ndefmacrop ",
                "\nif ",
                "\nunless ",
                "\nwhile ",
                "\ncase ",
                "\ncond ",
                "\nwith ",
                "\nfor ",
                "\ndo ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleRust() {
        return Arrays.asList(
                "\nfn ",
                "\nconst ",
                "\nlet ",
                "\nif ",
                "\nwhile ",
                "\nfor ",
                "\nloop ",
                "\nmatch ",
                "\nconst ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleScala() {
        return Arrays.asList(
                "\nclass ",
                "\nobject ",
                "\ndef ",
                "\nval ",
                "\nvar ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nmatch ",
                "\ncase ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleSwift() {
        return Arrays.asList(
                "\nfunc ",
                "\nclass ",
                "\nstruct ",
                "\nenum ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\ndo ",
                "\nswitch ",
                "\ncase ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleMarkdown() {
        return Arrays.asList(
                "\n#{1,6} ",
                "```\n",
                "\n\\*\\*\\*+\n",
                "\n---+\n",
                "\n___+\n",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleLatex() {
        return Arrays.asList(
                "\n\\\\chapter{",
                "\n\\\\section{",
                "\n\\\\subsection{",
                "\n\\\\subsubsection{",
                "\n\\\\begin{enumerate}",
                "\n\\\\begin{itemize}",
                "\n\\\\begin{description}",
                "\n\\\\begin{list}",
                "\n\\\\begin{quote}",
                "\n\\\\begin{quotation}",
                "\n\\\\begin{verse}",
                "\n\\\\begin{verbatim}",
                "\n\\\\begin{align}",
                "$$",
                "$",
                " ",
                ""
        );
    }

    private static List<String> handleCsharp() {
        return Arrays.asList(
                "\ninterface ",
                "\nenum ",
                "\nimplements ",
                "\ndelegate ",
                "\nevent ",
                "\nclass ",
                "\nabstract ",
                "\npublic ",
                "\nprotected ",
                "\nprivate ",
                "\nstatic ",
                "\nreturn ",
                "\nif ",
                "\ncontinue ",
                "\nfor ",
                "\nforeach ",
                "\nwhile ",
                "\nswitch ",
                "\nbreak ",
                "\ncase ",
                "\nelse ",
                "\ntry ",
                "\nthrow ",
                "\nfinally ",
                "\ncatch ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleSol() {
        return Arrays.asList(
                "\npragma ",
                "\nusing ",
                "\ncontract ",
                "\ninterface ",
                "\nlibrary ",
                "\nconstructor ",
                "\ntype ",
                "\nfunction ",
                "\nevent ",
                "\nmodifier ",
                "\nerror ",
                "\nstruct ",
                "\nenum ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\ndo while ",
                "\nassembly ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleCobol() {
        return Arrays.asList(
                "\nIDENTIFICATION DIVISION.",
                "\nENVIRONMENT DIVISION.",
                "\nDATA DIVISION.",
                "\nPROCEDURE DIVISION.",
                "\nWORKING-STORAGE SECTION.",
                "\nLINKAGE SECTION.",
                "\nFILE SECTION.",
                "\nINPUT-OUTPUT SECTION.",
                "\nOPEN ",
                "\nCLOSE ",
                "\nREAD ",
                "\nWRITE ",
                "\nIF ",
                "\nELSE ",
                "\nMOVE ",
                "\nPERFORM ",
                "\nUNTIL ",
                "\nVARYING ",
                "\nACCEPT ",
                "\nDISPLAY ",
                "\nSTOP RUN.",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleLua() {
        return Arrays.asList(
                "\nlocal ",
                "\nfunction ",
                "\nif ",
                "\nfor ",
                "\nwhile ",
                "\nrepeat ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handleHaskell() {
        return Arrays.asList(
                "\nmain :: ",
                "\nmain = ",
                "\nlet ",
                "\nin ",
                "\ndo ",
                "\nwhere ",
                "\n:: ",
                "\n= ",
                "\ndata ",
                "\nnewtype ",
                "\ntype ",
                "\nmodule ",
                "\nimport ",
                "\nqualified ",
                "\nimport qualified ",
                "\nclass ",
                "\ninstance ",
                "\ncase ",
                "\n| ",
                "\ndata ",
                "\n= {",
                "\n, ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    private static List<String> handlePowershell() {
        return Arrays.asList(
                "\nfunction ",
                "\nparam ",
                "\nif ",
                "\nforeach ",
                "\nfor ",
                "\nwhile ",
                "\nswitch ",
                "\nclass ",
                "\ntry ",
                "\ncatch ",
                "\nfinally ",
                "\n\n",
                "\n",
                " ",
                ""
        );
    }

    public enum Language {
        C, CPP, GO, JAVA, KOTLIN, JS, TS, PHP, PROTO, PYTHON, RST, RUBY,
        ELIXIR, RUST, SCALA, SWIFT, MARKDOWN, LATEX, HTML, CSHARP, SOL,
        COBOL, LUA, HASKELL, POWERSHELL
    }
}