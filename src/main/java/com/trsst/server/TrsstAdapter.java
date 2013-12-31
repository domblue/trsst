/*
 * Copyright 2013 mpowers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.trsst.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.i18n.templates.Template;
import org.apache.abdera.model.AtomDate;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Link;
import org.apache.abdera.model.Person;
import org.apache.abdera.parser.ParseException;
import org.apache.abdera.protocol.server.ProviderHelper;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.Target;
import org.apache.abdera.protocol.server.TargetType;
import org.apache.abdera.protocol.server.RequestContext.Scope;
import org.apache.abdera.protocol.server.context.MediaResponseContext;
import org.apache.abdera.protocol.server.context.ResponseContextException;
import org.apache.abdera.protocol.server.context.StreamWriterResponseContext;
import org.apache.abdera.security.AbderaSecurity;
import org.apache.abdera.security.Signature;
import org.apache.abdera.security.SignatureOptions;
import org.apache.abdera.util.Constants;
import org.apache.abdera.util.EntityTag;
import org.apache.abdera.util.MimeTypeHelper;
import org.apache.abdera.writer.StreamWriter;

import com.trsst.Common;

/**
 * Trsst-specific extensions to atompub, which mainly consists of accepting
 * Feeds instead of Entries, and validating that all Entries and Feeds are
 * signed.
 * 
 * Servers don't deal with encryption or private keys at all.
 * 
 * All persistence is delegated to an instance of Storage.
 * 
 * Callers may serve multiple request for the same feed id to the same
 * TrsstAdapter and instances might be retained in cache, so you need to be
 * thread-safe and resource-friendly.
 * 
 * @author mpowers
 */

public class TrsstAdapter extends AbstractMultipartAdapter {

    private final static Template paging_template = new Template(
            "{collection}?{-join|&|q,verb,mention,tag,before,after,count,page}");

    String accountId;
    Storage persistence;
    Map<String, String> accepts;

    /**
     * Callers may serve multiple request for the same feed id to the same
     * TrsstAdapter and instances might be retained in cache, so you need to be
     * thread-safe and resource-friendly.
     * 
     * @param id
     *            the feed id in question
     * @param storage
     *            the persistence engine
     * @throws FileNotFoundException
     *             if requested feed is not known to this server
     * @throws IOException
     *             for other kinds of persistence issues
     */
    public TrsstAdapter(String id, Storage storage)
            throws FileNotFoundException, IOException {
        this.accountId = id;
        persistence = storage;
    }

    protected Feed getFeed() throws ParseException, FileNotFoundException,
            IOException {
        return (Feed) Abdera.getInstance().getParser()
                .parse(new StringReader(persistence.readFeed(accountId)))
                .getRoot();
    }

    @Override
    public String getId(RequestContext request) {
        return accountId;
    }

    @Override
    public String getAuthor(RequestContext request)
            throws ResponseContextException {
        Person author = null;
        try {
            author = getFeed().getAuthor();
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + accountId, e);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + accountId, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + accountId, e);
        }
        if (author != null) {
            return author.getName();
        }
        return null;
    }

    @Override
    protected Feed createFeedBase(RequestContext request)
            throws ResponseContextException {
        try {
            return (Feed) getFeed().clone();
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + accountId, e);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + accountId, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + accountId, e);
        }
        return null;
    }

    public String getTitle(RequestContext request) {
        try {
            return getFeed().getTitle();
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + accountId, e);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + accountId, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + accountId, e);
        }
        return null;
    }

    public String getHref(RequestContext request) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("collection", getId(request));
        return request.urlFor(TargetType.TYPE_COLLECTION, params);
    }

    /**
     * Returns a feed document containing all the entries for this feed, subject
     * to pagination.
     */
    public ResponseContext getFeed(RequestContext request) {
        try {
            Feed result = getFeed();
            getEntries(request, result);
            return ProviderHelper.returnBase(result, 200, result.getUpdated())
                    .setEntityTag(ProviderHelper.calculateEntityTag(result));
        } catch (IllegalArgumentException e) {
            log.debug("Bad request: " + accountId, e);
            return ProviderHelper.badrequest(request, e.getMessage());
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + accountId, e);
            return ProviderHelper.notfound(request);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + accountId, e);
            return ProviderHelper.servererror(request, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + accountId, e);
            return ProviderHelper.servererror(request, e);
        }
    }

    /**
     * Returns a feed document containing the single requested entry. NOTE: this
     * is a deviation from atompub.
     */
    public ResponseContext getEntry(RequestContext request) {
        // make a copy of the current template
        Feed result;
        try {
            result = getFeed();
            // add requested entry
            String entryId = request.getTarget().getParameter("entry");
            Document<Entry> entry = getEntry(request, Common.toEntryId(entryId));
            if (entry != null) {
                result.addEntry(entry.getRoot());
            }
            return ProviderHelper.returnBase(result, 200, result.getUpdated())
                    .setEntityTag(ProviderHelper.calculateEntityTag(result));
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + accountId, e);
            return ProviderHelper.notfound(request);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + accountId, e);
            return ProviderHelper.servererror(request, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + accountId, e);
            return ProviderHelper.servererror(request, e);
        }
    }

    private Document<Entry> getEntry(RequestContext context, long entryId) {
        try {
            return context
                    .getAbdera()
                    .getParser()
                    .parse(new StringReader(persistence.readEntry(accountId,
                            entryId)));
        } catch (FileNotFoundException fnfe) {
            // fall through
        } catch (Exception e) {
            log.error("Unexpected error: " + accountId + " : " + entryId, e);
        }
        return null;
    }

    /**
     * Accepts a signed feed document containing one or more signed entries and
     * attachments for some or all of those entries.
     */
    public ResponseContext postMedia(RequestContext request) {
        try {
            if (MimeTypeHelper.isMultipart(request.getContentType().toString())) {
                List<MultipartRelatedPost> posts = getMultipartRelatedData(request);
                Feed incomingFeed = null;
                if (posts != null) {
                    Map<String, Entry> contentIdToEntry = new HashMap<String, Entry>();
                    Map<String, String> contentIdToType = new HashMap<String, String>();
                    Map<String, InputStream> contentIdToData = new HashMap<String, InputStream>();
                    for (MultipartRelatedPost post : posts) {
                        String type = post.getDataHeaders().get("content-type");
                        String cid = post.getDataHeaders().get("content-id");
                        if (cid != null) {
                            if (cid.startsWith("<cid:")) {
                                cid = cid.substring(5);
                                cid = cid.substring(0, cid.length() - 1);
                            }
                            // find content id in entry list
                            List<Entry> entries;
                            if (post.getSource().getRoot() instanceof Feed) {
                                incomingFeed = ((Feed) post.getSource()
                                        .getRoot());
                                entries = incomingFeed.getEntries();
                            } else if (post.getSource().getRoot() instanceof Entry) {
                                log.warn("Single entries not supported: "
                                        + post.getSource().getRoot());
                                entries = new LinkedList<Entry>();
                                entries.add((Entry) post.getSource().getRoot());
                                return ProviderHelper
                                        .badrequest(request,
                                                "Single entries not currently supported.");
                            } else {
                                log.error("Unrecognized source: "
                                        + post.getSource());
                                return ProviderHelper.badrequest(request,
                                        "Unrecognized source.");
                            }
                            for (Entry entry : entries) {
                                if (entry.getContentSrc() != null
                                        && entry.getContentSrc().toString()
                                                .endsWith(cid)) {
                                    // getContentSrc resolves against baseurl
                                    contentIdToEntry.put(cid, entry);
                                    contentIdToType.put(cid, type);
                                    contentIdToData.put(cid, post.getData());
                                }
                            }
                        }
                    }
                    // if all content ids match an entry content element
                    if (contentIdToEntry.size() == posts.size()) {
                        processFeed(incomingFeed);
                        for (Map.Entry<String, Entry> i : contentIdToEntry
                                .entrySet()) {
                            String cid = i.getKey();
                            Entry entry = i.getValue();
                            persistence.updateFeedEntryResource(accountId,
                                    Common.toEntryId(entry.getId()), cid,
                                    contentIdToType.get(cid),
                                    entry.getPublished(),
                                    contentIdToData.get(cid));
                        }
                        return ProviderHelper.returnBase(incomingFeed, 201,
                                null);
                    }
                }
            }
        } catch (Exception pe) {
            log.error("postMedia: ", pe);
        }
        return ProviderHelper.badrequest(request,
                "Content ids did not match entry content ids");
    }

    /**
     * Validate then persist incoming feed and entries. Any exception thrown
     * means no feed or entries are persisted.
     * 
     * @param feed
     *            with zero or more entries to be validated and persisted.
     * @throws XMLSignatureException
     *             if signature verification fails
     * @throws IllegalArgumentException
     *             if data validation fails
     * @throws Exception
     *             any other problem
     */
    protected void processFeed(Feed feed) throws XMLSignatureException,
            IllegalArgumentException, Exception {

        // clone a copy so we can manipulate
        feed = (Feed) feed.clone();

        // validate feed
        Date lastUpdated = feed.getUpdated();
        if (lastUpdated == null) {
            throw new IllegalArgumentException(
                    "Feed update timestamp is required: " + accountId);
        }
        if (lastUpdated.after(new Date())) {
            throw new IllegalArgumentException(
                    "Feed update timestamp cannot be in the future: "
                            + accountId);
        }

        // grab the signing key
        Element signingElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.SIGN));
        if (signingElement == null) {
            throw new XMLSignatureException(
                    "Could not find signing key for feed: " + accountId);
        }

        // verify that the key matches the id
        PublicKey publicKey = Common.toPublicKeyFromX509(signingElement
                .getText());
        if (Common.fromFeedUrn(feed.getId()) == null
                || !Common.fromFeedUrn(feed.getId()).equals(
                        Common.toFeedId(publicKey))) {
            throw new XMLSignatureException(
                    "Signing key does not match feed id: "
                            + Common.fromFeedUrn(feed.getId()));
        }

        // prep the verifier
        AbderaSecurity security = new AbderaSecurity(Abdera.getInstance());
        Signature signature = security.getSignature();
        SignatureOptions options = signature.getDefaultSignatureOptions();
        options.setSigningAlgorithm("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1");
        options.setSignLinks(false);
        options.setPublicKey(publicKey);

        // validate, persist, and remove each entry
        List<Entry> entries = new LinkedList<Entry>();
        entries.addAll(feed.getEntries()); // make a copy
        for (Entry entry : entries) {
            if (!signature.verify(entry, options)) {
                log.warn("Could not verify signature for entry with id: "
                        + accountId);
                throw new XMLSignatureException(
                        "Could not verify signature for entry with id: "
                                + entry.getId() + " : " + accountId);
            }
            // remove from feed parent
            entry.discard();
        }
        // setEditDetail(request, entry, key);
        // String edit = entry.getEditLinkResolvedHref().toString();

        // remove all links before verifying
        for (Link link : feed.getLinks()) {
            link.discard();
        }
        // remove all opensearch elements before verifying
        for (Element e : feed
                .getExtensions("http://a9.com/-/spec/opensearch/1.1/")) {
            e.discard();
        }

        // now validate feed signature sans entries
        if (!signature.verify(feed, options)) {
            log.warn("Could not verify signature for feed with id: "
                    + accountId);
            throw new XMLSignatureException(
                    "Could not verify signature for feed with id: " + accountId);
        }

        // persist feed
        persistence.updateFeed(accountId, feed.getUpdated(), feed.toString());
        // only now persist each entry
        for (Entry entry : entries) {
            Date date = entry.getPublished();
            if (date == null) {
                // fall back to updated if publish not set
                date = entry.getUpdated();
            }
            persistence.updateEntry(accountId, Common.toEntryId(entry.getId()),
                    date, entry.toString());
        }
    }

    /**
     * Accepts a signed feed document containing one or more signed entries. All
     * signatures must be valid or the entire transaction will be rejected.
     * NOTE: this is a deviation from atompub.
     */
    public ResponseContext postEntry(RequestContext request) {
        if (request.isAtom()) {
            try {
                // we require a feed entity (not solo entries like atompub)
                Feed incomingFeed = (Feed) request.getDocument().getRoot();
                processFeed(incomingFeed);
                return ProviderHelper.returnBase(incomingFeed, 201, null);
            } catch (XMLSignatureException xmle) {
                log.error("Could not verify signature: ", xmle);
                return ProviderHelper.badrequest(request,
                        "Could not verify signature: " + xmle.getMessage());
            } catch (FileNotFoundException fnfe) {
                return ProviderHelper.notfound(request, "Not found: "
                        + accountId);
            } catch (Exception e) {
                log.warn("Bad request: " + accountId, e);
                return ProviderHelper.badrequest(request, e.toString());
            }
        } else {
            return ProviderHelper.notsupported(request);
        }
    }

    /**
     * PUT operations are treated as POST operations. NOTE: this is a deviation
     * from atompub.
     */
    public ResponseContext putEntry(RequestContext request) {
        return postEntry(request);
    }

    /**
     * DELETE operations should instead replace the entry is a placeholder
     * expiration notice to maintain blogchain integrity. NOTE: this is a
     * deviation from atompub.
     */
    public ResponseContext deleteEntry(RequestContext request) {
        // TODO: post distribution revocation entry referencing the specified
        // entry
        Target target = request.getTarget();
        String entryId = target.getParameter("entry");
        try {
            persistence.deleteEntry(accountId, Common.toEntryId(entryId));
        } catch (IOException ioe) {
            return ProviderHelper.servererror(request, ioe);
        }
        return ProviderHelper.nocontent();
    }

    public ResponseContext extensionRequest(RequestContext request) {
        return ProviderHelper.notallowed(request, "Method Not Allowed",
                ProviderHelper.getDefaultMethods(request));
    }

    /**
     * Categories map to feed ids available on this server. This might be only
     * the feeds belonging to a server's "registered users" or all feeds cached
     * by a server or some logical place inbetween.
     */
    public ResponseContext getCategories(RequestContext request) {
        return new StreamWriterResponseContext(request.getAbdera()) {
            protected void writeTo(StreamWriter sw) throws IOException {
                sw.startDocument().startCategories(false);
                for (String id : persistence.getFeedIds(0, 100)) {
                    sw.writeCategory(id);
                }
                sw.endCategories().endDocument();
            }
        }.setStatus(200).setContentType(Constants.CAT_MEDIA_TYPE);
    }

    private void getEntries(RequestContext context, Feed feed)
            throws FileNotFoundException, IOException {
        String searchTerms = (String) context.getAttribute(Scope.REQUEST,
                "OpenSearch__searchTerms");
        Date beginDate = null;

        String verb = context.getParameter("verb");

        String[] mentions = null;
        List<String> mentionList = context.getParameters("mention");
        if (mentionList != null) {
            mentions = mentionList.toArray(new String[0]);
        }

        String[] tags = null;
        List<String> tagList = context.getParameters("tag");
        if (tagList != null) {
            tags = tagList.toArray(new String[0]);
        }

        String after = context.getParameter("after");
        if (after != null) {
            String begin = after;
            String beginTemplate = "0000-01-01T00:00:00.000Z";
            if (begin.length() < beginTemplate.length()) {
                begin = begin + beginTemplate.substring(begin.length());
            }
            try {
                beginDate = new AtomDate(begin).getDate();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Could not parse begin date: " + begin);
            }
        }
        Date endDate = null;
        String before = context.getParameter("before");
        if (before != null) {
            String end = before;
            String endTemplate = "9999-12-31T23:59:59.999Z";
            if (end.length() < endTemplate.length()) {
                end = end + endTemplate.substring(end.length() + 1);
            }
            try {
                endDate = new AtomDate(end).getDate();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Could not parse end date: "
                        + end);
            }
        }

        int length = ProviderHelper.getPageSize(context, "count", 25);
        // int offset = ProviderHelper.getOffset(context, "page", length);
        int maxresults = 999; // arbitrary: clients that need larger should page
                              // themselves by date
        String _page = context.getParameter("page");
        int page = (_page != null) ? Integer.parseInt(_page) : 0;
        long[] entryIds = persistence.getEntryIdsForFeedId(accountId, 0,
                maxresults, beginDate, endDate, searchTerms, mentions, tags,
                verb);
        addPagingLinks(context, feed, page, length, entryIds.length,
                searchTerms, before, after, mentions, tags, verb);
        int start = page * length;
        int end = Math.min(entryIds.length, start + length);
        for (int i = start; i < end; i++) {
            Entry entry = getEntry(context, entryIds[i]).getRoot();
            feed.addEntry((Entry) entry.clone());
        }
    }

    private void addPagingLinks(RequestContext request, Feed feed,
            int currentPage, int itemsPerPage, int totalCount,
            String searchTerms, String before, String after, String[] mentions,
            String[] tags, String verb) {
        Map<String, Object> params = new HashMap<String, Object>();
        if (searchTerms != null) {
            params.put("q", searchTerms);
        }
        if (before != null) {
            params.put("before", before);
        }
        if (after != null) {
            params.put("after", after);
        }
        if (mentions != null) {
            // hack: template doesn't support duplicate keys with different
            // values
            String value = mentions[0];
            for (int i = 1; i < mentions.length; i++) {
                value = value + "&mention=" + mentions[i];
            }
            params.put("mention", value);
            // FIXME: this doesn't even work because string gets escaped
        }
        if (tags != null) {
            // hack: template doesn't support duplicate keys with different
            // values
            String value = tags[0];
            for (int i = 1; i < tags.length; i++) {
                value = value + "&tag=" + tags[i];
            }
            params.put("tag", value);
            // FIXME: this doesn't even work because string gets escaped
        }
        params.put("collection", request.getTarget().getParameter("collection"));
        params.put("count", itemsPerPage);
        params.put("page", currentPage);

        String current = paging_template.expand(params);
        current = request.getResolvedUri().resolve(current).toString();
        feed.addLink(current, "current");
        if (totalCount > (currentPage + 1) * itemsPerPage) {
            params.put("page", currentPage + 1);
            String next = paging_template.expand(params);
            next = request.getResolvedUri().resolve(next).toString();
            feed.addLink(next, "next");
        }
        if (currentPage > 0) {
            params.put("page", currentPage - 1);
            String prev = paging_template.expand(params);
            prev = request.getResolvedUri().resolve(prev).toString();
            feed.addLink(prev, "previous");
        }

        // add opensearch tags
        feed.addSimpleExtension(new QName(
                "http://a9.com/-/spec/opensearch/1.1/", "totalResults",
                "opensearch"), Integer.toString(totalCount));
        feed.addSimpleExtension(new QName(
                "http://a9.com/-/spec/opensearch/1.1/", "startIndex",
                "opensearch"), Integer.toString(currentPage * itemsPerPage + 1));
        feed.addSimpleExtension(new QName(
                "http://a9.com/-/spec/opensearch/1.1/", "itemsPerPage",
                "opensearch"), Integer.toString(itemsPerPage));

    }

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

    public Map<String, String> getAlternateAccepts(RequestContext request) {
        if (accepts == null) {
            accepts = new HashMap<String, String>();
            accepts.put("video/*", null); /* doesn't accept multipart related */
            accepts.put("image/png", Constants.LN_ALTERNATE_MULTIPART_RELATED);
            accepts.put("image/jpeg", Constants.LN_ALTERNATE_MULTIPART_RELATED);
            accepts.put("image/gif", Constants.LN_ALTERNATE_MULTIPART_RELATED);
            accepts.put("image/svg+xml",
                    Constants.LN_ALTERNATE_MULTIPART_RELATED);
        }
        return accepts;
    }

    /**
     * Get a media resource
     */
    @Override
    public ResponseContext getMedia(RequestContext request) {
        String feedId = request.getTarget().getParameter("collection");
        String entryId = request.getTarget().getParameter("entry");
        String resourceId = request.getTarget().getParameter("resource");
        InputStream input;
        try {
            input = persistence.readFeedEntryResource(feedId,
                    Common.toEntryId(entryId), resourceId);
            return new MediaResponseContext(input, new EntityTag(resourceId),
                    200);
        } catch (FileNotFoundException e) {
            return ProviderHelper.notfound(request);
        } catch (IOException e) {
            return ProviderHelper.badrequest(request,
                    "Could not parse resource request");
        }
    }

    /**
     * Get metdata for a media resource
     */
    @Override
    public ResponseContext headMedia(RequestContext request) {
        // TODO: implement HEAD support
        return getMedia(request);
    }

}