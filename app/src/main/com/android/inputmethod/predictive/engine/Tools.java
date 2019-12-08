package com.android.inputmethod.predictive.engine;

import javax.annotation.Nonnull;

import java.util.Collections;
import java.util.Vector;

public class Tools {
    boolean debug = false;

    /**
     * Note: this class has a natural ordering that is inconsistent with equals.
     */
    public class Word implements Comparable<Word> {
        public int occurrence = 0;
        public String word = "";
        public String next_word = "";

        /**
         * taken directly from ~/Android\Sdk\sources\android-28\java\lang\Integer.java
         * Compares two {@code int} values numerically.
         * The value returned is identical to what would be returned by:
         * <pre>
         *    Integer.valueOf(x).compareTo(Integer.valueOf(y))
         * </pre>
         *
         * @param  x the first {@code int} to compare
         * @param  y the second {@code int} to compare
         * @return the value {@code 0} if {@code x == y};
         *         a value less than {@code 0} if {@code x < y}; and
         *         a value greater than {@code 0} if {@code x > y}
         * @since 1.7
         */
        @SuppressWarnings("UseCompareMethod")
        public int compare(int x, int y) {
            return (x < y) ? -1 : ((x == y) ? 0 : 1);
        }

        @Override
        public int compareTo(@Nonnull Word o) {
            return compare(occurrence, o.occurrence);
        }
    }

    public static class Database {
        public Vector<Word> words = new Vector<>();
        public String last_word = "";
        public Vector<String> history = new Vector<>();
        public Vector<Word> predictions = new Vector<>();
        public String prediction_word = "";

        public void reset() {
            words.clear();
            last_word = "";
            predictions.clear();
            engine.print("Database reset");
        }
    }

    public boolean split(@Nonnull String in, @Nonnull Vector<String> out) {
        Vector<Character> word = new Vector<>();
        for (Character c: in.toCharArray()) {
            if (c != ' ') {
                word.add(c);
            } else {
                if (word.size() != 0) {
                    // vector to String
                    StringBuilder w = new StringBuilder();
                    for (Character character: word)
                        w.append(character);
                    out.add(w.toString());
                    word.clear();
                }
            }
        }
        // a word may be at the end of the string "abcd", this would not get triggered in the loop
        // because there is no space after it
        if (word.size() != 0) {
            // vector to String
            StringBuilder w = new StringBuilder();
            for (Character character: word)
                w.append(character);
            out.add(w.toString());
            word.clear();
        }
        return !out.isEmpty();
    }

    public void add_word(@Nonnull String in, @Nonnull Database out) {
        if (debug) engine.print("creating new word");
        Word w = new Word();
        w.word = in;
        w.occurrence++;
        out.words.add(w);
        if (debug) engine.print("created new word");
    }

    public void analyze(@Nonnull String in, @Nonnull Database out) {
        engine.print("input: " + in);
        Vector<String> result = new Vector<>();
        if (split(in, result)) process(result, out);
    }

    public void process(@Nonnull Vector<String> in, @Nonnull Database out) {
        processWords(in, out);
    }

    public void processWords(@Nonnull Vector<String> in, @Nonnull Database out) {
        // basic word prediction
        int inputSize = in.size();
        for (int i = 0; i != inputSize; i++) {
            if (debug) engine.print("in[" + i + "] = " + in.get(i));
            out.history.add(in.get(i));
            // find last word
            if (!out.last_word.equals("")) {
                // a // ""
                // b // "a"
                // c // "b"
                if (debug) engine.print("searching for word (analysis): " + out.last_word);
                int outputSize = out.words.size();
                boolean containsWord = false;
                for (int o = 0; o != outputSize; o++) {
                    if (out.words.get(o).word.equals(out.last_word)) {
                        // a word has been found
                        if (debug) engine.print("found word at index: " + o);
                        // ensure the next word is this word
                        if (out.words.get(o).next_word.equals(in.get(i))) {
                            containsWord = true;
                            if (debug) engine.print("next word is this word");
                            out.words.get(o).occurrence++;
                            break;
                        } else if (out.words.get(o).next_word.equals("")) {
                            if (debug) engine.print("next word is empty");
                            out.words.get(o).next_word = in.get(i);
                        }
                    }
                }
                if (!containsWord) add_word(in.get(i), out);
            } else add_word(in.get(i), out);
            out.last_word = in.get(i);
        }
    }

    void print(@Nonnull Database in) {
        engine.print("Analysis Database size: " + in.words.size());
        for (int i = 0; i != in.words.size(); i++) {
            Word w = in.words.get(i);
            engine.print("index: " + i
                    + ", word: " + w.word
                    + ", next word: " + w.next_word
                    + ", occurrence: " + w.occurrence
            );
        }
    }

    void printHistory(@Nonnull Database in) {
        engine.print("History Database size: " + in.history.size());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i != in.history.size()-1; i++) {
            out.append(in.history.get(i)).append(" ");
        }
        out.append(in.history.get(in.history.size() - 1));
        engine.print(out.toString());
    }

    void predictNextWord(Database in) {
        predictNextWord(in.last_word, in);
    }

    void predictNextWord(String in, Database out) {
        out.prediction_word = in;
        // basic word prediction: predicting
        if (debug) engine.print("searching for word (prediction): " + in);
        int sizeOut = out.words.size();
        Vector<Integer> indexes = new Vector<>();
        // find all words beginning with the input word
        for (int o = 0; o != sizeOut; o++) {
            if (out.words.get(o).word.equals(in)) {
                // a word has been found
                if (debug) engine.print("found word at index: " + o);
                if (!out.words.get(o).next_word.equals("")) {
                    if (debug) engine.print("next word is not empty");
                    indexes.add(o);
                }
            }
        }
        // now that we have all words that begin with the given input
        // we sort by occurrence from highest to lowest
        out.predictions.clear();
        for (int index : indexes) out.predictions.add(out.words.get(index));
        Collections.sort(out.predictions);
        Collections.reverse(out.predictions);
    }

    void printPredictions(Database in) {
        if (in.predictions.size() == 0) return;
        engine.print("predictions for word: " + in.prediction_word);
        if (debug) {
            engine.print("Prediction Database size: " + in.predictions.size());
            for (int i = 0; i != in.predictions.size(); i++) {
                engine.print("index: " + i
                         + ", next word: " + in.predictions.get(i).next_word
                         + ", occurrence: " + in.predictions.get(i).occurrence);
            }
        } else {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i != in.predictions.size()-1; i++) {
                out.append(in.predictions.get(i).next_word).append(", ");
            }
            out.append(in.predictions.get(in.predictions.size() - 1).next_word);
            engine.print(out.toString());
        }
    }

    public static void test() {
        Tools x = new Tools();
        x.debug = true;
        Database database = new Tools.Database();
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.analyze("a a b b b a a b b a c a c c a c c c c c a c a  c a c a c a c a a c a k a k a k a k a k a k", database);
        x.print(database);
        x.predictNextWord("a", database);
        x.printPredictions(database);
        database.reset();
    }
}
