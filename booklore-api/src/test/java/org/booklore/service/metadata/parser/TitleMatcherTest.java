package org.booklore.service.metadata.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleMatcherTest {

    @Test
    void matchesSameTitleWithSeriesNoiseOnTheCandidate() {
        assertThat(TitleMatcher.matches("Married Beauty Enjoys the Werewolf",
                "Married Beauty Enjoys the Werewolf: (Werewolf's Hot MILF Harem 2)")).isTrue();
        assertThat(TitleMatcher.matches("MILF Neighborhood: Part Two",
                "MILF Neighborhood: Part Two (The Alpha Society Book 2)")).isTrue();
    }

    @Test
    void numberWordsAndDigitsAgree() {
        assertThat(TitleMatcher.matches("Rock God: Part Three", "Rock God: Part 3")).isTrue();
    }

    @Test
    void differentVolumeOfTheSameSeriesDoesNotMatch() {
        assertThat(TitleMatcher.matches("Rock God: Part Three", "Rock God: Part Two")).isFalse();
        assertThat(TitleMatcher.matches("Rock God: Part Three", "Rock God")).isFalse();
        assertThat(TitleMatcher.matches("My Best Friend's Mom Has A Secret 3", "My Best Friend's Mom Has A Secret 2")).isFalse();
    }

    @Test
    void bundleDoesNotMatchOneOfItsBooks() {
        assertThat(TitleMatcher.matches("Rock God: Part Two", "Rock God: Parts One to Six")).isFalse();
        assertThat(TitleMatcher.matches("Stand-In Groom", "My Best Friend's Daughters: 3-Book Box Set")).isFalse();
        assertThat(TitleMatcher.matches("MILF Neighborhood: Part One", "MILF Neighborhood: Complete Books 1-5")).isFalse();
        assertThat(TitleMatcher.matches("Strikin' It Rich", "Mountain Man's Harem: Omnibus Edition")).isFalse();
        assertThat(TitleMatcher.matches("Succubus Sisterhood: Part One", "Succubus Sisterhood: The Bundle")).isFalse();
    }

    @Test
    void unrelatedTitlesDoNotMatch() {
        assertThat(TitleMatcher.matches("Awakened by the Sultry Shifter", "Married Beauty Enjoys the Werewolf")).isFalse();
    }

    @Test
    void toleratesATypoAndFileNameNoise() {
        assertThat(TitleMatcher.matches("he Fallen Angel's Naughty Plan", "The Fallen Angel's Naughty Plan")).isTrue();
        assertThat(TitleMatcher.matches("Rock God Part Three Nick Storming", "Rock God: Part Three")).isTrue();
    }

    @Test
    void blankTitlesCannotBeJudgedAndMatch() {
        assertThat(TitleMatcher.matches(null, "Anything")).isTrue();
        assertThat(TitleMatcher.matches("Anything", " ")).isTrue();
    }
}
