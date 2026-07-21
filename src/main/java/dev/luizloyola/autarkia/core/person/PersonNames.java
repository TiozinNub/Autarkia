package dev.luizloyola.autarkia.core.person;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Generates names for new persons.
 *
 * <p>Deterministic for a given {@link RandomGenerator}, so name assignment is unit-testable.
 * Given names are gendered (a new person draws from the pool for their {@link Gender}); surnames
 * are a shared pool reserved for a future family/lineage system. Placeholder starter sets, kept
 * separate from skin choice. Culture/biome variation comes later.
 *
 * <p>Sources: given names aggregated by long-run popularity from US SSA data (1880–2008), split by
 * the recorded sex; surnames from the US Census most-common-surnames list.
 */
public final class PersonNames {
    private PersonNames() {}

    /** Common male given names, drawn by {@link #random(RandomGenerator, Gender)} for {@code MALE}. */
    static final List<String> MALE_FIRST_NAMES = List.of(
            "Aaron", "Adam", "Alan", "Albert", "Alex", "Alexander", "Alfred", "Allen",
            "Andrew", "Anthony", "Arthur", "Austin", "Benjamin", "Bernard", "Billy", "Bobby",
            "Brandon", "Brian", "Bruce", "Bryan", "Carl", "Charles", "Charlie", "Chester",
            "Christian", "Christopher", "Clarence", "Clifford", "Clyde", "Curtis", "Dale", "Daniel",
            "David", "Dennis", "Donald", "Douglas", "Earl", "Eddie", "Edgar", "Edward",
            "Edwin", "Elmer", "Eric", "Ernest", "Eugene", "Floyd", "Francis", "Frank",
            "Fred", "Frederick", "Gary", "George", "Gerald", "Glenn", "Gregory", "Harold",
            "Harry", "Henry", "Herbert", "Herman", "Howard", "Jack", "Jacob", "James",
            "Jason", "Jeffrey", "Jeremy", "Jerry", "Jesse", "Jim", "Jimmy", "Joe",
            "John", "Johnny", "Jonathan", "Jose", "Joseph", "Joshua", "Juan", "Justin",
            "Keith", "Kenneth", "Kevin", "Kyle", "Larry", "Lawrence", "Lee", "Leo",
            "Leonard", "Leroy", "Lester", "Lewis", "Lloyd", "Louis", "Mark", "Martin",
            "Marvin", "Matthew", "Melvin", "Michael", "Nathan", "Nicholas", "Norman", "Oscar",
            "Patrick", "Paul", "Peter", "Philip", "Phillip", "Ralph", "Randy", "Ray",
            "Raymond", "Richard", "Robert", "Roger", "Ronald", "Roy", "Russell", "Ryan",
            "Sam", "Samuel", "Scott", "Sean", "Stanley", "Stephen", "Steven", "Terry",
            "Theodore", "Thomas", "Timothy", "Tom", "Tyler", "Victor", "Vincent", "Walter",
            "Wayne", "William", "Willie", "Zachary");

    /** Common female given names, drawn by {@link #random(RandomGenerator, Gender)} for {@code FEMALE}. */
    static final List<String> FEMALE_FIRST_NAMES = List.of(
            "Agnes", "Alice", "Alma", "Amanda", "Amy", "Andrea", "Angela", "Ann",
            "Anna", "Anne", "Annie", "Ashley", "Barbara", "Beatrice", "Bertha", "Bessie",
            "Betty", "Beverly", "Bonnie", "Brenda", "Carol", "Carolyn", "Carrie", "Catherine",
            "Charlotte", "Cheryl", "Christina", "Christine", "Clara", "Cora", "Cynthia", "Deborah",
            "Debra", "Diane", "Donna", "Doris", "Dorothy", "Edith", "Edna", "Eleanor",
            "Elizabeth", "Ella", "Ellen", "Elsie", "Emily", "Emma", "Esther", "Ethel",
            "Eva", "Evelyn", "Florence", "Frances", "Gertrude", "Gladys", "Gloria", "Grace",
            "Hannah", "Hattie", "Hazel", "Heather", "Helen", "Ida", "Irene", "Jacqueline",
            "Jane", "Janet", "Janice", "Jean", "Jennie", "Jennifer", "Jessica", "Jessie",
            "Joan", "Josephine", "Joyce", "Judith", "Judy", "Julia", "Julie", "Karen",
            "Katherine", "Kathleen", "Kathryn", "Kelly", "Kimberly", "Laura", "Lauren", "Lena",
            "Lillian", "Lillie", "Linda", "Lisa", "Lois", "Louise", "Lucille", "Lucy",
            "Mabel", "Margaret", "Maria", "Marie", "Marilyn", "Marion", "Marjorie", "Martha",
            "Mary", "Mattie", "Megan", "Melissa", "Michelle", "Mildred", "Minnie", "Myrtle",
            "Nancy", "Nellie", "Nicole", "Norma", "Pamela", "Patricia", "Pauline", "Pearl",
            "Phyllis", "Rachel", "Rebecca", "Rosa", "Rose", "Ruby", "Ruth", "Samantha",
            "Sandra", "Sara", "Sarah", "Sharon", "Shirley", "Stephanie", "Susan", "Teresa",
            "Thelma", "Theresa", "Victoria", "Virginia");

    /**
     * Common surnames. Reserved for when persons gain family/lineage names — see
     * {@link #randomSurname(RandomGenerator)}; not yet used anywhere.
     */
    static final List<String> SURNAMES = List.of(
            "Adams", "Alexander", "Allen", "Alvarez", "Anderson", "Bailey", "Baker", "Barnes",
            "Bell", "Bennett", "Black", "Brooks", "Brown", "Bryant", "Burns", "Butler",
            "Campbell", "Carter", "Castillo", "Chavez", "Clark", "Cole", "Coleman", "Collins",
            "Cook", "Cooper", "Cox", "Crawford", "Cruz", "Davis", "Diaz", "Dixon",
            "Edwards", "Ellis", "Evans", "Fisher", "Flores", "Ford", "Foster", "Freeman",
            "Garcia", "Gibson", "Gomez", "Gonzales", "Gonzalez", "Gordon", "Graham", "Gray",
            "Green", "Griffin", "Gutierrez", "Hall", "Hamilton", "Harris", "Harrison", "Hayes",
            "Henderson", "Henry", "Hernandez", "Hicks", "Hill", "Howard", "Hughes", "Hunt",
            "Hunter", "Jackson", "James", "Jenkins", "Jimenez", "Johnson", "Jones", "Jordan",
            "Kelly", "Kennedy", "Kim", "King", "Lee", "Lewis", "Long", "Lopez",
            "Marshall", "Martin", "Martinez", "Mason", "McDonald", "Mendoza", "Miller", "Mitchell",
            "Moore", "Morales", "Morgan", "Morris", "Murphy", "Murray", "Myers", "Nelson",
            "Nguyen", "Olson", "Ortiz", "Owens", "Palmer", "Parker", "Patterson", "Perez",
            "Perry", "Peterson", "Phillips", "Porter", "Powell", "Price", "Ramirez", "Ramos",
            "Reed", "Reyes", "Reynolds", "Richardson", "Rivera", "Roberts", "Robertson", "Robinson",
            "Rodriguez", "Rogers", "Romero", "Ross", "Ruiz", "Russell", "Sanchez", "Sanders",
            "Scott", "Shaw", "Simmons", "Simpson", "Smith", "Snyder", "Stevens", "Stewart",
            "Sullivan", "Taylor", "Thomas", "Thompson", "Torres", "Tucker", "Turner", "Vasquez",
            "Wagner", "Walker", "Wallace", "Ward", "Washington", "Watson", "Webb", "Wells",
            "West", "White", "Williams", "Wilson", "Wood", "Woods", "Wright", "Young");

    /** A random given name appropriate to {@code gender}. */
    public static String random(RandomGenerator random, Gender gender) {
        List<String> pool = gender.choose(MALE_FIRST_NAMES, FEMALE_FIRST_NAMES);
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * A random surname. Reserved for a future family-name system; unused for now.
     */
    public static String  randomSurname(RandomGenerator random) {
        return SURNAMES.get(random.nextInt(SURNAMES.size()));
    }
}
