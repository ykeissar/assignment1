import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;


public class Worker {

    private String queueUrl;
    private AmazonSQS sqs;
   // private AWSCredentialsProvider credentialsProvider;//TODO delete


    public Worker(String queueUrl) {
        this.queueUrl = queueUrl;

//        credentialsProvider = new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials()); //TODO delete

        sqs = AmazonSQSClientBuilder.standard()
                .withRegion(Regions.US_WEST_2)
    //            .withCredentials(credentialsProvider)//TODO delete
                .build();
    }

    //----------------------------------AWS------------------------------------
    public Message readMessagesLookForFirstLine(String lookFor) {
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(queueUrl);
        receiveMessageRequest.withWaitTimeSeconds(5);
        List<Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();
        for (Message message : messages) {
            String firstLine = message.getBody().substring(0, message.getBody().indexOf("\n"));
            if (firstLine.equals(lookFor)) {
                return message;
            }
        }
        return null;
    }

    public void sendMessage(String message) {
        sqs.sendMessage(new SendMessageRequest(queueUrl, message));
        System.out.println(String.format("Sending message '%s' to queue with url - %s.", message, queueUrl));
    }

    public void deleteMessage(Message message){
        sqs.deleteMessage(queueUrl, message.getReceiptHandle());
    }


    //-----------------------------MessageProcess------------------------------

    public String processReview(String review) {
        String toReturn = review.substring(3);

        int sentiment = findSentiment(review);

        //coloring review
        switch(sentiment) {
            case 0: {
                toReturn = "<font color=#930000>" + toReturn + "</font>";
                break;
            }
            case 1: {
                toReturn = "<font color=#FF0000>" + toReturn + "</font>";
                break;
            }
            case 2: {
                toReturn = "<font color=#000000>" + toReturn + "</font>";
                break;
            }
            case 3: {
                toReturn = "<font color=#0FFF00>" + toReturn + "</font>";
                break;
            }
            case 4: {
                toReturn = "<font color=#088300>" + toReturn + "</font>";
                break;
            }
        }

        int sarcastic = findRating(review) - sentiment;
        toReturn+=getEntities(review);
        return sarcastic > 3 ? toReturn + " sarcastic\n" : toReturn+" not_sarcastic\n";
    }

    public static int findRating(String review) {
        JSONParser parser = new JSONParser();
        JSONObject obj2 = null;
        try {
            obj2 = (JSONObject) parser.parse(review);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        Long rating = (Long) obj2.get("rating");
        return rating.intValue();
    }

    public static int findSentiment(String review) {
        Properties props = new Properties();
        props.put("annotators", "tokenize, ssplit, parse, sentiment");
        StanfordCoreNLP sentimentPipeline = new StanfordCoreNLP(props);

        int mainSentiment = 0;
        if (review != null && review.length() > 0) {
            int longest = 0;
            Annotation annotation = sentimentPipeline.process(review);
            for (CoreMap sentence : annotation
                    .get(CoreAnnotations.SentencesAnnotation.class)) {
                Tree tree = sentence.get(SentimentCoreAnnotations.AnnotatedTree.class);
                int sentiment = RNNCoreAnnotations.getPredictedClass(tree);
                String partText = sentence.toString();
                if (partText.length() > longest) {
                    mainSentiment = sentiment;
                    longest = partText.length();
                }

            }
        }
        return mainSentiment;
    }

    public String getEntities(String review){
        StringBuilder entities = new StringBuilder().append(" [");
        List<String> entitiesToKeep = new ArrayList<String>();
        entitiesToKeep.add("PERSON");
        entitiesToKeep.add("LOCATION");
        entitiesToKeep.add("ORGANIZATION");

        Properties props = new Properties();
        props.put("annotators", "tokenize , ssplit, pos, lemma, ner");
        StanfordCoreNLP NERPipeline =  new StanfordCoreNLP(props);

        // create an empty Annotation just with the given text
        Annotation document = new Annotation(review);

        // run all Annotators on this text
        NERPipeline.annotate(document);

        // these are all the sentences in this document
        // a CoreMap is essentially a Map that uses class objects as keys and has values with custom types
        List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);

        for(CoreMap sentence: sentences) {
            // traversing the words in the current sentence
            // a CoreLabel is a CoreMap with additional token-specific methods
            for (CoreLabel token: sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                // this is the text of the token
                String word = token.get(CoreAnnotations.TextAnnotation.class);
                // this is the NER label of the token
                String ne = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
                if(entitiesToKeep.contains(ne))
                    entities.append(ne).append(":").append(word).append(",");
            }
        }
        return entities.append("]").toString();
    }

    public static void main(String[] args) {
        String s = "\n" +
                "\t{\n" +
                "      \"author\": \"Nikki J\",\n" +
                "      \"date\": \"2017-05-01T21:00:00.000Z\",\n" +
                "      \"id\": \"R14D3WP6J91DCU\",\n" +
                "      \"link\": \"https://www.amazon.com/gp/customer-reviews/R14D3WP6J91DCU/ref=cm_cr_arp_d_rvw_ttl?ie=UTF8&ASIN=0689835604\",\n" +
                "      \"rating\": 5,\n" +
                "      \"text\": \"Super cute book. My son loves lifting the flaps.\",\n" +
                "      \"title\": \"Five Stars\"\n" +
                "    }";

//        System.out.println(findRating(s));
        Worker w = new Worker("");
        System.out.println(w.processReview(s));
    }

    public String tempProcessReview(String review) {//TODO delete
        String toReturn = review.substring(3);
        int sentiment = new Random().nextInt(5);
        int rating = findRating(toReturn);
        //coloring review
        switch(sentiment) {
            case 0: {
                toReturn = "<font color=#930000>" + toReturn + "</font>";
                break;
            }
            case 1: {
                toReturn = "<font color=#FF0000>" + toReturn + "</font>";
                break;
            }
            case 2: {
                toReturn = "<font color=#000000>" + toReturn + "</font>";
                break;
            }
            case 3: {
                toReturn = "<font color=#0FFF00>" + toReturn + "</font>";
                break;
            }
            case 4: {
                toReturn = "<font color=#088300>" + toReturn + "</font>";
                break;
            }
        }

        int sarcastic = rating - sentiment;
        return sarcastic > 3 ? toReturn + " sarcastic\n" : toReturn+" not_sarcastic\n";
    }
}
