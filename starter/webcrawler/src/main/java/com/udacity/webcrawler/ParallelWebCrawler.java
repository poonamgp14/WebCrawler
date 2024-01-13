package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final int maxDepth;
  private final ForkJoinPool pool;
  private final List<Pattern> ignoredUrls;
  private final PageParserFactory parserFactory;

  @Inject
  ParallelWebCrawler(
      Clock clock,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount, int maxDepth,List<Pattern> ignoredUrls
      ,PageParserFactory parserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
    this.parserFactory = parserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {

    Instant deadline = clock.instant().plus(timeout);
    ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
    for (String url : startingUrls) {

      pool.invoke(new CrawlInternal(url, deadline, maxDepth, counts, visitedUrls));
    }

    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }

    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();
  }

  @Override
  public int getMaxParallelism() {

    return Runtime.getRuntime().availableProcessors();
  }
  private final class CrawlInternal extends RecursiveAction {
    private final String link;
    private final Instant deadline;
    private final int maxDepth;
    private final ConcurrentHashMap<String, Integer> counts;
    private final ConcurrentSkipListSet<String> visitedUrls;

    public CrawlInternal(String link, Instant deadline, int maxDepth, ConcurrentHashMap<String, Integer> count, ConcurrentSkipListSet<String> visitedUrls) {
      this.link = link;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.counts = count;
      this.visitedUrls = visitedUrls;
    }

    @Override
    protected void compute() {
      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return;
      }
      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(link).matches()) {
          continue;
        }
      }

      if (visitedUrls.contains(link)) {
        return;
      }
      visitedUrls.add(link);
      PageParser.Result result = parserFactory.get(link).parse();
      for (ConcurrentHashMap.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
        counts.compute(e.getKey(), (k,v) -> (v == null) ? e.getValue(): e.getValue() + v);
      }
      List<CrawlInternal> subtasks = new ArrayList<>();
      for (String url : result.getLinks()) {
        subtasks.add(new CrawlInternal(url, deadline, maxDepth - 1, counts, visitedUrls));
      }
      invokeAll(subtasks);
    }
  }

}
