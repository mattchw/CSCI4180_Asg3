import java.io.*;

import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;

import java.util.*;
import java.math.BigInteger;
import java.lang.ClassNotFoundException;

import com.microsoft.azure.storage.*;
import com.microsoft.azure.storage.blob.*;

import java.net.URISyntaxException;

public class Azure{
  public static final String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=csci4180group28;AccountKey=AL9Qxr3OnckMBPgKe2g1QHcpzvCRg3Y+4yMekGOmFcJq9YPLoPe7FbwydK0IzpF//haaxV/gcq7fVd05dH+bDA==;EndpointSuffix=core.windows.net";

  public static CloudStorageAccount storageAccount;
  public static CloudBlobClient blobClient;
  public static CloudBlobContainer blobContainer;
  public static String containerName = "mycontainer";

  public static final String indexFileName = "mydedup.index";
  public static final String recipesFileName = "fileRecipes.index";

  // CUHK Network
  // static {
  //       System.setProperty("https.proxyHost", "proxy.cse.cuhk.edu.hk");
  //       System.setProperty("https.proxyPort", "8000");
  //       System.setProperty("http.proxyHost", "proxy.cse.cuhk.edu.hk");
  //       System.setProperty("http.proxyPort", "8000");
  // }

  public Azure(){
    try {
        storageAccount = CloudStorageAccount.parse(storageConnectionString);
        blobClient = storageAccount.createCloudBlobClient();
        blobContainer = blobClient.getContainerReference(containerName);
        blobContainer.createIfNotExists();
    } catch (Exception ae) {
        System.err.println(ae.getMessage());
        ae.printStackTrace();
    }
	}

  public static int exp_mod(int base, int exp, int modulus) {
    base %= modulus;
    int result = 1;
    while (exp > 0) {
      if ((exp & 1) != 0) result = (result * base) % modulus;
      base = (base * base) % modulus;
      exp >>= 1;
    }
    return result;
  }

  public static void deleteDir(File file) {
      File[] contents = file.listFiles();
      if (contents != null) {
          for (File f : contents) {
              deleteDir(f);
          }
      }
      file.delete();
  }

  public void upload(int minChunk, int avgChunk, int maxChunk, int d, String fileToUpload, String storageType) throws IOException, NoSuchAlgorithmException, ClassNotFoundException, URISyntaxException{
    File file = new File(fileToUpload);
    try {
    long fileSize = file.length();

    FileInputStream fis = new FileInputStream(file);
    FileRecipeList FileRecipeList = new FileRecipeList();
    List<String> fileRecipe = new ArrayList<>();
    IndexList IndexList = new IndexList();
    //for local
    File dir = null;

    FileOutputStream indexFileOut = null;
    ObjectOutputStream indexObjOut = null;
    FileOutputStream recipesFileOut = null;
    ObjectOutputStream recipesObjOut = null;

    dir = new File("store");

    // error handle
    if (!dir.exists()) {
        if (!dir.mkdir()) {
            System.err.println("Failed to create directory \"store\"");
            return;
        }
    }
    if (!dir.isDirectory()) {
        System.err.println("\"store\" is not a directory");
        return;
    }
    CloudBlockBlob blockBlobReference = blobContainer.getBlockBlobReference(indexFileName);

      if (blockBlobReference.exists()) {
          blockBlobReference.downloadToFile(dir.getName() + "/" + indexFileName);
      }

      FileInputStream fileIn;
      ObjectInputStream objIn;

      // index file

      File indexFile = new File(dir.getName() + "/" + indexFileName);
      if (indexFile.exists()){
        System.out.println("index file exisits");
        fileIn = new FileInputStream(indexFile.getAbsolutePath());
        objIn = new ObjectInputStream(fileIn);
        IndexList = (IndexList) objIn.readObject();
        objIn.close();
        fileIn.close();
      } else {
        indexFile.createNewFile();
      }
      indexFileOut = new FileOutputStream(indexFile.getAbsolutePath());
      indexObjOut = new ObjectOutputStream(indexFileOut);

      // receipes file

      File recipesFile = new File(dir.getName() + "/" + recipesFileName);
      if (recipesFile.exists()){
        System.out.println("recipes file exisits");
        fileIn = new FileInputStream(recipesFile.getAbsolutePath());
        objIn = new ObjectInputStream(fileIn);
        FileRecipeList = (FileRecipeList) objIn.readObject();
        objIn.close();
        fileIn.close();
      } else {
        recipesFile.createNewFile();
      }

      if (FileRecipeList.fileRecipes.containsKey(fileToUpload)) {
          System.err.println("Error, file already exists!");
      }
      recipesFileOut = new FileOutputStream(recipesFile.getAbsolutePath());
      recipesObjOut = new ObjectOutputStream(recipesFileOut);

    int maxBufferSize = (int) Runtime.getRuntime().freeMemory();
    int chunkIterations = (int) Math.ceil((1.0 * fileSize / maxBufferSize));      // count times to chunk large files
    int lastChunkSize = (int) (fileSize % maxBufferSize);
    long totalLogicChunks = 0;
    long totalUniqueChunks = 0;
    long totalLogicFileBytes = 0;
    long totalUniqueFileBytes = 0;
    double spaceSaving = 0;

    for (int iteration = 0; iteration < chunkIterations; iteration++) {
        byte[] fileBytes;
        if (iteration == chunkIterations - 1) {
            fileBytes = new byte[lastChunkSize];
        } else {
            fileBytes = new byte[maxBufferSize];
        }
        fis.read(fileBytes);
        int fileChunkSize = fileBytes.length;
        int m = minChunk;
        int q = avgChunk;
        int s = 0;
        int currentChunkSize = 0;
        int currentPos = 0;
        int fp = 0;
        List<Integer> offset = new ArrayList<>();
        List<Integer> chunkSize = new ArrayList<>();
        while ((currentPos + currentChunkSize <= fileChunkSize) && (currentPos + m <= fileChunkSize)) {
            if (s == 0) {
                fp = 0;
                for (int i = 1; i <= m; i++) {
                    fp = (fp + ((int) (fileBytes[currentPos + i - 1] & 0xff) * exp_mod(d, m - i, q))) % q;
                }
                currentChunkSize = m;
            } else {

                fp = (d * (fp - exp_mod(d, m - 1, q) * (int) (fileBytes[currentPos + s - 1] & 0xff)) + fileBytes[currentPos + s + m - 1]) % q;
                while (fp < 0) {
                    fp += q;
                }
                currentChunkSize++;
            }
            if ((fp & 0xFF) == 0 || currentChunkSize == maxChunk || currentPos + currentChunkSize >= fileChunkSize) {
                // match anchor point/ reach max chunk size/ reach end of file chunk, produce one anchor point
                offset.add(currentPos);
                chunkSize.add(currentChunkSize);
                currentPos += currentChunkSize;
                s = 0;
                currentChunkSize = 0;
            } else {
                s++;
            }
        }
        // handle special case: chunk smaller than m left behind
        if (currentPos < fileChunkSize) {
            offset.add(currentPos);
            chunkSize.add(fileChunkSize - currentPos);
        }
        if (offset.size() == chunkSize.size() && offset.size() > 0) {
            int numOfChunks = offset.size();

            // Do some file settings here
            for (int i = 0; i < numOfChunks; i++) {
              MessageDigest md = MessageDigest.getInstance("SHA-256");
              md.update(Arrays.copyOfRange(fileBytes, offset.get(i), offset.get(i) + chunkSize.get(i)));

              byte[] messageDigest = md.digest();

              BigInteger no = new BigInteger(1, messageDigest);

              String hashtext = no.toString(16);

              while (hashtext.length() < 32) {
                  hashtext = "0" + hashtext;
              }

                if (IndexList.index.containsKey(hashtext)) { // already have that chunk, reuse it

                    IndexList.index.get(hashtext).refCount += 1;
                    fileRecipe.add(hashtext);
                } else {         // if no entry of this chunk in indexTable, do update, create new chunk
                    Index index = new Index();

                    index.chunkSize = chunkSize.get(i);

                    CloudBlockBlob blob = blobContainer.getBlockBlobReference(hashtext);
                    blob.uploadFromByteArray(fileBytes, offset.get(i), chunkSize.get(i));
                    IndexList.index.put(hashtext, new Index(chunkSize.get(i), 1));
                    fileRecipe.add(hashtext);
                }
            }

        }
    }
    // statistics:
    for (String key : IndexList.index.keySet()) {
        int refCount = IndexList.index.get(key).refCount;
        int chunkSize = IndexList.index.get(key).chunkSize;
        totalLogicChunks += refCount;
        totalUniqueChunks++;
        totalLogicFileBytes += refCount * chunkSize;
        totalUniqueFileBytes += chunkSize;
    }
    spaceSaving = 1 - 1.0 * totalUniqueChunks / totalLogicChunks;
    System.out.println("Total number of chunks in storage: " + totalLogicChunks);
    System.out.println("Number of unique chunks in storage: " + totalUniqueChunks);
    System.out.println("Number of bytes in storage with deduplication: " + totalUniqueFileBytes);
    System.out.println("Number of bytes in storage without deduplication: " + totalLogicFileBytes);
    System.out.println("Space saving: " + spaceSaving);


    FileRecipeList.fileRecipes.put(fileToUpload, fileRecipe);

    indexObjOut.writeObject(IndexList);
    indexObjOut.close();
    indexFileOut.close();

    recipesObjOut.writeObject(FileRecipeList);
    recipesObjOut.close();
    recipesFileOut.close();

    CloudBlockBlob blockBlobReference = blobContainer.getBlockBlobReference(indexFileName);
    File source = new File(dir.getName() + "/" + indexFileName);

    blockBlobReference.uploadFromFile(source.getAbsolutePath());

    CloudBlockBlob recipeBlockBlobReference = blobContainer.getBlockBlobReference(recipesFileName);
    File recipe = new File(dir.getName() + "/" + recipesFileName);

    recipeBlockBlobReference.uploadFromFile(recipe.getAbsolutePath());
    deleteDir(dir);
  } catch (Exception e) {
      e.printStackTrace();
  }

  }

  public void download(String fileToDownload, String storageType)throws IOException{
    String downloadedFileName = fileToDownload + ".download";
    try {
        FileRecipeList FileRecipeList = new FileRecipeList();
        List<String> fileRecipe = new ArrayList<>();
        IndexList IndexList = new IndexList();


        //for local
        File dir;

        dir = new File("store");
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                System.err.println("Failed to create directory \"store\"");
                System.err.println("Program terminated");
                return;
            }
        }
        if (!dir.isDirectory()) {
            System.err.println("\"store\" is not a directory!");
            return;
        }
        FileInputStream fileIn;
        ObjectInputStream objIn;

        CloudBlockBlob blockBlobReference = blobContainer.getBlockBlobReference(indexFileName);

        if (blockBlobReference.exists()) {
            blockBlobReference.download(new FileOutputStream(dir.getName() + "/" + indexFileName));
        }
        File indexFile = new File(dir.getName() + "/" + indexFileName);
        Boolean isNewIndexFile = indexFile.createNewFile();


        if (!isNewIndexFile) {
            fileIn = new FileInputStream(indexFile.getAbsolutePath());
            objIn = new ObjectInputStream(fileIn);
            IndexList = (IndexList) objIn.readObject();
            objIn.close();
            fileIn.close();
        }

        CloudBlockBlob recipeBlockBlobReference = blobContainer.getBlockBlobReference(recipesFileName);

        if (recipeBlockBlobReference.exists()) {
            recipeBlockBlobReference.download(new FileOutputStream(dir.getName() + "/" + recipesFileName));
        }
        File recipesFile = new File(dir.getName() + "/" + recipesFileName);
        Boolean isNewRecipesFile = recipesFile.createNewFile();
        if (!isNewRecipesFile) {
            fileIn = new FileInputStream(recipesFile.getAbsolutePath());
            objIn = new ObjectInputStream(fileIn);
            FileRecipeList = (FileRecipeList) objIn.readObject();
            objIn.close();
            fileIn.close();
        }

        FileInputStream fis;
        byte[] fileBytes;
        int bytesRead = 0;
        FileOutputStream fos = new FileOutputStream(new File(downloadedFileName));
        List<String> recipeList = FileRecipeList.fileRecipes.get(fileToDownload);
        for (String chunkName : recipeList) {
            CloudBlockBlob blob = blobContainer.getBlockBlobReference(chunkName);

            File file = new File(dir.getName() + "/" + chunkName);
            blob.downloadToFile(file.getAbsolutePath());
            fis = new FileInputStream(file);
            fileBytes = new byte[(int) file.length()];
            bytesRead = fis.read(fileBytes, 0, (int) file.length());
            fos.write(fileBytes);
            fos.flush();
            fileBytes = null;
            fis.close();
            fis = null;
        }
        fos.close();
        fos = null;
        deleteDir(dir);
    } catch (Exception e) {
        System.err.println(e.getMessage());
    }
  }

  public void delete(String fileToDelete, String storageType) throws IOException, NoSuchAlgorithmException, ClassNotFoundException{
    try {
        FileRecipeList FileRecipeList = new FileRecipeList();
        List<String> fileRecipe = new ArrayList<>();
        IndexList IndexList = new IndexList();
        //for local
        File dir;

        dir = new File("store");
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                System.err.println("Failed to create directory \"store\"");
                System.err.println("Program terminated");
                return;
            }
        }
        if (!dir.isDirectory()) {
            System.err.println("\"store\" is not a directory!");
            return;
        }
        FileInputStream fileIn;
        ObjectInputStream objIn;

        CloudBlockBlob blockBlobReference = blobContainer.getBlockBlobReference(indexFileName);

        if (blockBlobReference.exists()) {
            blockBlobReference.downloadToFile(dir.getName() + "/" + indexFileName);
        }
        File indexFile = new File(dir.getName() + "/" + indexFileName);
        Boolean isNewIndexFile = indexFile.createNewFile();

        FileOutputStream indexFileOut = null;
        ObjectOutputStream indexObjOut = null;
        FileOutputStream recipesFileOut = null;
        ObjectOutputStream recipesObjOut = null;

        if (!isNewIndexFile) {
            fileIn = new FileInputStream(indexFile.getAbsolutePath());
            objIn = new ObjectInputStream(fileIn);
            IndexList = (IndexList) objIn.readObject();
            objIn.close();
            fileIn.close();
        }
        indexFileOut = new FileOutputStream(indexFile.getAbsolutePath());
        indexObjOut = new ObjectOutputStream(indexFileOut);

        CloudBlockBlob recipeBlockBlobReference = blobContainer.getBlockBlobReference(recipesFileName);

        if (recipeBlockBlobReference.exists()) {
            recipeBlockBlobReference.downloadToFile(dir.getName() + "/" + recipesFileName);
        }
        File recipesFile = new File(dir.getName() + "/" + recipesFileName);
        Boolean isNewRecipesFile = recipesFile.createNewFile();
        if (!isNewRecipesFile) {
            fileIn = new FileInputStream(recipesFile.getAbsolutePath());
            objIn = new ObjectInputStream(fileIn);
            FileRecipeList = (FileRecipeList) objIn.readObject();
            objIn.close();
            fileIn.close();
        }

        recipesFileOut = new FileOutputStream(recipesFile.getAbsolutePath());
        recipesObjOut = new ObjectOutputStream(recipesFileOut);

        List<String> recipeList = FileRecipeList.fileRecipes.get(fileToDelete);

        for (String sha1Hex : recipeList) {
            IndexList.index.get(sha1Hex).refCount -= 1;
            if (IndexList.index.get(sha1Hex).refCount == 0) {
                IndexList.index.remove(sha1Hex);
                CloudBlockBlob blob = blobContainer.getBlockBlobReference(sha1Hex);
                FileRecipeList.fileRecipes.remove(fileToDelete);
                blob.deleteIfExists();
            }
        }

        indexObjOut.writeObject(IndexList);
        indexObjOut.close();
        indexFileOut.close();

        recipesObjOut.writeObject(FileRecipeList);
        recipesObjOut.close();
        recipesFileOut.close();

        CloudBlockBlob indexFileNameBlobReference = blobContainer.getBlockBlobReference(indexFileName);
        File source = new File(dir.getName() + "/" + indexFileName);
        indexFileNameBlobReference.uploadFromFile(source.getAbsolutePath());

        CloudBlockBlob recipesFileNameBlobReference = blobContainer.getBlockBlobReference(recipesFileName);
        File recipe = new File(dir.getName() + "/" + recipesFileName);
        recipesFileNameBlobReference.uploadFromFile(recipe.getAbsolutePath());

        deleteDir(dir);

    } catch (Exception e) {
        System.err.println(e.getMessage());
    }
  }
}