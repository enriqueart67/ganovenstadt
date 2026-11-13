package de.peregrine.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Map;

/**
 * Eigene 5×7-Pixelschrift aus Rechtecken. Funktioniert schon, bevor Minecraft seine Schriften
 * und Texturen geladen hat (Ladebildschirm beim Start).
 */
public final class PixelFont {
    private static final Map<Character, String[]> G = Map.ofEntries(
            Map.entry('A', new String[]{".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"}),
            Map.entry('B', new String[]{"####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."}),
            Map.entry('C', new String[]{".####", "#....", "#....", "#....", "#....", "#....", ".####"}),
            Map.entry('D', new String[]{"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."}),
            Map.entry('E', new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#####"}),
            Map.entry('F', new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#...."}),
            Map.entry('G', new String[]{".####", "#....", "#....", "#.###", "#...#", "#...#", ".###."}),
            Map.entry('H', new String[]{"#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"}),
            Map.entry('I', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"}),
            Map.entry('J', new String[]{"..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."}),
            Map.entry('K', new String[]{"#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"}),
            Map.entry('L', new String[]{"#....", "#....", "#....", "#....", "#....", "#....", "#####"}),
            Map.entry('M', new String[]{"#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"}),
            Map.entry('N', new String[]{"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"}),
            Map.entry('O', new String[]{".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."}),
            Map.entry('P', new String[]{"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."}),
            Map.entry('Q', new String[]{".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"}),
            Map.entry('R', new String[]{"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"}),
            Map.entry('S', new String[]{".####", "#....", "#....", ".###.", "....#", "....#", "####."}),
            Map.entry('T', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."}),
            Map.entry('U', new String[]{"#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."}),
            Map.entry('V', new String[]{"#...#", "#...#", "#...#", "#...#", ".#.#.", ".#.#.", "..#.."}),
            Map.entry('W', new String[]{"#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"}),
            Map.entry('X', new String[]{"#...#", ".#.#.", "..#..", "..#..", "..#..", ".#.#.", "#...#"}),
            Map.entry('Y', new String[]{"#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..", "..#.."}),
            Map.entry('Z', new String[]{"#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"}),
            Map.entry('0', new String[]{".###.", "#..##", "#.#.#", "#.#.#", "#.#.#", "##..#", ".###."}),
            Map.entry('1', new String[]{"..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."}),
            Map.entry('2', new String[]{".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"}),
            Map.entry('3', new String[]{"####.", "....#", "....#", ".###.", "....#", "....#", "####."}),
            Map.entry('4', new String[]{"...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."}),
            Map.entry('5', new String[]{"#####", "#....", "####.", "....#", "....#", "#...#", ".###."}),
            Map.entry('6', new String[]{".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."}),
            Map.entry('7', new String[]{"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."}),
            Map.entry('8', new String[]{".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."}),
            Map.entry('9', new String[]{".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."}),
            Map.entry('%', new String[]{"##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"}),
            Map.entry('.', new String[]{".....", ".....", ".....", ".....", ".....", ".##..", ".##.."}),
            Map.entry('-', new String[]{".....", ".....", ".....", "#####", ".....", ".....", "....."}),
            Map.entry('Ä', new String[]{"#...#", ".###.", "#...#", "#####", "#...#", "#...#", "#...#"}),
            Map.entry('Ö', new String[]{"#...#", ".###.", "#...#", "#...#", "#...#", "#...#", ".###."}),
            Map.entry('Ü', new String[]{"#...#", ".....", "#...#", "#...#", "#...#", "#...#", ".###."}));

    private PixelFont() {
    }

    /** Breite des Textes in Pixeln bei `scale` (ein Glyph = 5 Punkte + 1 Abstand; Leerzeichen = 4). */
    public static int width(String s, int scale, int spacing) {
        int w = 0;
        for (char ch : s.toUpperCase().toCharArray()) w += (ch == ' ' ? 4 : 5) * scale + spacing;
        return Math.max(0, w - spacing);
    }

    public static void draw(GuiGraphicsExtractor g, String s, int x, int y, int scale, int spacing, int argb) {
        int cx = x;
        for (char ch : s.toUpperCase().toCharArray()) {
            if (ch == ' ') {
                cx += 4 * scale + spacing;
                continue;
            }
            String[] rows = G.get(ch);
            if (rows != null) {
                for (int r = 0; r < 7; r++) {
                    String row = rows[r];
                    int run = -1;
                    for (int c = 0; c <= 5; c++) {
                        boolean on = c < 5 && row.charAt(c) == '#';
                        if (on && run < 0) run = c;
                        if (!on && run >= 0) { // zusammenhaengende Punkte als ein Rechteck
                            g.fill(cx + run * scale, y + r * scale, cx + c * scale, y + (r + 1) * scale, argb);
                            run = -1;
                        }
                    }
                }
            }
            cx += 5 * scale + spacing;
        }
    }
}
